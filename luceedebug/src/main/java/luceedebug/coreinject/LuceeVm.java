package luceedebug.coreinject;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import com.google.common.collect.MapMaker;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static luceedebug.coreinject.Iife.iife;

import luceedebug.*;
import luceedebug.strong.DapBreakpointID;
import luceedebug.strong.JdwpThreadID;
import luceedebug.strong.CanonicalServerAbsPath;
import luceedebug.strong.RawIdePath;

public class LuceeVm implements ILuceeVm {
    // This is a key into a map stored on breakpointRequest objects; the value should always be of Integer type
    // "step finalization" breakpoints will not have this, so lookup against it will yield null
    final static private String LUCEEDEBUG_BREAKPOINT_ID = "luceedebug-breakpoint-id";
    final static private String LUCEEDEBUG_BREAKPOINT_EXPR = "luceedebug-breakpoint-expr";

    private final Config config_;
    private final VirtualMachine vm_;

    /**
     * A multimap of (jdwp threadID -> jvm Thread) & (jvm Thread -> jdwp ThreadRef)
     */
    private static class ThreadMap {
        private final Cleaner cleaner = Cleaner.create();

        private final ConcurrentHashMap<JdwpThreadID, WeakReference<Thread>> threadByJdwpId = new ConcurrentHashMap<>();
        private final ConcurrentMap<Thread, ThreadReference> threadRefByThread = new MapMaker()
            .concurrencyLevel(/* default as per docs */ 4)
            .weakKeys()
            .makeMap();

        public Thread getThreadByJdwpId(JdwpThreadID jdwpId) {
            var weakRef = threadByJdwpId.get(jdwpId);
            if (weakRef == null) {
                return null;
            }
            else {
                return weakRef.get();
            }
        }

        private Thread getThreadByJdwpIdOrFail(JdwpThreadID id) {
            var thread = getThreadByJdwpId(id);
            if (thread != null) {
                return thread;
            }
            System.out.println("[luceedebug] couldn't find thread with id '" + id + "'");
            System.exit(1);
            return null;
        }

        public ThreadReference getThreadRefByThread(Thread thread) {
            return threadRefByThread.get(thread);
        }

        public ThreadReference getThreadRefByThreadOrFail(Thread thread) {
            var result = getThreadRefByThread(thread);
            if (result != null) {
                return result;
            }
            System.out.println("[luceedebug] couldn't find thread reference for thread " + thread );
            System.exit(1);
            return null;
        }

        public ThreadReference getThreadRefByJdwpIdOrFail(JdwpThreadID jdwpID) {
            return getThreadRefByThreadOrFail(getThreadByJdwpIdOrFail(jdwpID));
        }
        
        public void register(Thread thread, ThreadReference threadRef) {
            final var threadID = JdwpThreadID.of(threadRef);
            threadByJdwpId.put(threadID, new WeakReference<>(thread));
            threadRefByThread.put(thread, threadRef);
            cleaner.register(thread, () -> {
                // Manually remove from (threadID -> WeakRef<Thread>) mapping
                // The (WeakRef<Thread> -> ThreadRef) map should be autocleaning by virtue of "weakKeys"
                threadByJdwpId.remove(threadID);
            });
        }

        public void unregister(ThreadReference threadRef) {
            var threadID = JdwpThreadID.of(threadRef);
            var thread = getThreadByJdwpId(threadID);
            threadByJdwpId.remove(threadID);
            if (thread != null) {
                threadRefByThread.remove(thread);
            }
        }
    }

    private static class ReplayableCfBreakpointRequest {
        final RawIdePath ideAbsPath;
        final CanonicalServerAbsPath serverAbsPath;
        final int line;
        final DapBreakpointID id;
        /**
         * expression for conditional breakpoints
         * can be null for "not a conditional breakpoint"
         **/
        final String expr;

        /**
         * The implication is that a breakpoint is bound if we found a location for it an issued
         * a jdwp breakpoint request; but can we further interrogate the jdwp breakpoint request
         * and ask it if it itself is bound? Does `isEnabled` yield that, or is that just "we asked for it to be enabled"?
         */
        final BreakpointRequest maybeNull_jdwpBreakpointRequest;

        @Override
        public boolean equals(Object vv) {
            if (!(vv instanceof ReplayableCfBreakpointRequest)) {
                return false;
            }
            var v = (ReplayableCfBreakpointRequest)vv;

            return ideAbsPath.equals(v.ideAbsPath)
                && serverAbsPath.equals(v.serverAbsPath)
                && line == v.line
                && id == v.id
                && (expr == null ? v.expr == null : expr.equals(v.expr));
        }
        
        ReplayableCfBreakpointRequest(RawIdePath ideAbsPath, CanonicalServerAbsPath serverAbsPath, int line, DapBreakpointID id, String expr) {
            this.ideAbsPath = ideAbsPath;
            this.serverAbsPath = serverAbsPath;
            this.line = line;
            this.id = id;
            this.expr = expr;
            this.maybeNull_jdwpBreakpointRequest = null;
        }

        ReplayableCfBreakpointRequest(RawIdePath ideAbsPath, CanonicalServerAbsPath serverAbsPath, int line, DapBreakpointID id, String expr, BreakpointRequest jdwpBreakpointRequest) {
            this.ideAbsPath = ideAbsPath;
            this.serverAbsPath = serverAbsPath;
            this.line = line;
            this.id = id;
            this.expr = expr;
            this.maybeNull_jdwpBreakpointRequest = jdwpBreakpointRequest;
        }

        static List<BreakpointRequest> getJdwpRequests(Collection<ReplayableCfBreakpointRequest> vs) {
            return vs
                .stream()
                .filter(v -> v.maybeNull_jdwpBreakpointRequest != null)
                .map(v -> v.maybeNull_jdwpBreakpointRequest)
                .collect(Collectors.toList());
        }

        static BpLineAndId[] getLineInfo(Collection<ReplayableCfBreakpointRequest> vs) {
            return vs
                .stream()
                .map(v -> new BpLineAndId(v.ideAbsPath, v.serverAbsPath, v.line, v.id, v.expr))
                .toArray(size -> new BpLineAndId[size]);
        }
    }

    private final ThreadMap threadMap_ = new ThreadMap();
    private final ExecutorService stepHandlerExecutor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<CanonicalServerAbsPath, Set<ReplayableCfBreakpointRequest>> replayableBreakpointRequestsByAbsPath_ = new ConcurrentHashMap<>();
    
    /**
     * Mapping of "abspath on disk" -> "class file info"
     * Where a single path on disk can map to zero-or-more associated class files.
     * Usually there is only 1 classfile per abspath, but runtime mappings can mean that a single file
     * like "/app/foo.cfc" maps to "myapp.foo" as well as "someOtherMapping.foo", where each mapping
     * is represented by a separate classfile.
     */
    private final ConcurrentHashMap<CanonicalServerAbsPath, Set<KlassMap>> klassMap_ = new ConcurrentHashMap<>();
    private long JDWP_WORKER_CLASS_ID = 0;
    private ThreadReference JDWP_WORKER_THREADREF = null;

    private final JdwpStaticCallable jdwp_getThread;

    private static class JdwpStaticCallable {
        public final ClassType classType;
        public final Method method;
        public JdwpStaticCallable(ClassType classType, Method method) {
            this.classType = classType;
            this.method = method;
        }
    }

    private static class JdwpWorker {
        static void touch() {
            // Just load this class.
            // Intent is to be in the caller's class loader.
        }

        private static void jdwp_stays_suspended_in_this_method_as_a_worker() {
            // bp will be set on single return bytecode
            return;
        }

        static void spawnThreadForJdwpToSuspend() {
            new Thread(JdwpWorker::jdwp_stays_suspended_in_this_method_as_a_worker, "luceedebug-worker").start();
        }

        static ConcurrentHashMap<Long, Thread> threadBuffer_ = new ConcurrentHashMap<>();
        static AtomicLong threadBufferId_ = new AtomicLong();

        /**
         * call via jdwp, when the caller has only a jdwp ThreadReference
         * it places the actual thread object into a buffer, returning the key to retrieve it from that buffer
         * This allows us to grab the actual Thread the ThreadReference is referencing
         */
        @SuppressWarnings("unused") // only called indirectly, via jdwp `invokeMethod`
        static long jdwp_getThread(Thread thread) {
            long nextId = threadBufferId_.incrementAndGet();
            threadBuffer_.put(nextId, thread);
            return nextId;
        }

        /**
         * given a key from jdwp_getThread, return the actual results
         */
        static Thread jdwp_getThreadResult(long id) {
            var thread = threadBuffer_.get(id);
            threadBuffer_.remove(id);
            return thread;
        }

        private static volatile boolean ack = false;

        /**
         * we expect to do this exactly once per jvm startup,
         * so it shouldn't be too wasteful
         */
        static void spinWaitForJdwpBpToSuspendWorkerThread() {
            while (!ack);
        }

        static void notifyJdwpSuspendedWorkerThread() {
            ack = true;
        }
    }

    private void bootThreadTracking() {
        final var threadStartRequest = vm_.eventRequestManager().createThreadStartRequest();
        
        // Should we suspend thread start event threads?
        // Is there a perf hit to doing so?
        // We can catch the ObjectCollectedExceptions that happen if they get collected prior to us doing
        // work against it, and event with SUSPEND_EVENT_THREAD we were somehow hitting ObjectCollectedExceptions.
        threadStartRequest.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        threadStartRequest.enable();
        initCurrentThreadListing();

        final var threadDeathRequest = vm_.eventRequestManager().createThreadDeathRequest();
        threadDeathRequest.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        threadDeathRequest.enable();
    }

    private void bootClassTracking() {
        final var pageRef = vm_.classesByName("lucee.runtime.Page");

        if (pageRef.size() == 0) {
            var request = vm_.eventRequestManager().createClassPrepareRequest();
            request.addClassFilter("lucee.runtime.Page");
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            request.setEnabled(true);
        }
        else if (pageRef.size() == 1) {
            // we hoped it would be this easy, but when we initialize LuceeVm, lucee.runtime.Page is probably not loaded yet
            bootClassTracking(pageRef.get(0));
        }
        else {
            System.out.println("[luceedebug] Expected 0 or 1 ref for class with name 'lucee.runtime.Page', but got " + pageRef.size());
            System.exit(1);
        }
    }

    private void bootClassTracking(ReferenceType lucee_runtime_Page) {
        final var classPrepareRequest = vm_.eventRequestManager().createClassPrepareRequest();

        classPrepareRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        classPrepareRequest.addClassFilter(lucee_runtime_Page);
        classPrepareRequest.enable();

        final var classUnloadRequest = vm_.eventRequestManager().createClassUnloadRequest();
        classUnloadRequest.setSuspendPolicy(EventRequest.SUSPEND_NONE);
    }

    private JdwpStaticCallable bootThreadWorker() {
        JdwpWorker.touch();

        final String className = "luceedebug.coreinject.LuceeVm$JdwpWorker";
        final var refs = vm_.classesByName(className);
        if (refs.size() != 1) {
            System.out.println("Expected 1 ref for class " + className + " but got " + refs.size());
            System.exit(1);
        }

        final var refType = refs.get(0);

        Method jdwp_stays_suspended_in_this_method_as_a_worker = null;
        Method jdwp_getThread = null;
        for (var method : refType.methods()) {
            if (method.name().equals("jdwp_stays_suspended_in_this_method_as_a_worker")) {
                jdwp_stays_suspended_in_this_method_as_a_worker = method;
            }
            if (method.name().equals("jdwp_getThread")) {
                jdwp_getThread = method;
            }
        }

        if (jdwp_stays_suspended_in_this_method_as_a_worker == null) {
            System.out.println("Couldn't find helper method 'jdwp_stays_suspended_in_this_method_as_a_worker'");
            System.exit(1);
            return null;
        }
        if (jdwp_getThread == null) {
            System.out.println("Couldn't find helper method 'jdwp_getThread'");
            System.exit(1);
            return null;
        }

        JDWP_WORKER_CLASS_ID = refType.classObject().uniqueID();

        var bpRequest = vm_.eventRequestManager().createBreakpointRequest(jdwp_stays_suspended_in_this_method_as_a_worker.locationOfCodeIndex(0));
        bpRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        bpRequest.enable();
        // this should spawn a thread, and hit a breakpoint in that thread
        JdwpWorker.spawnThreadForJdwpToSuspend();
        // breakpoint event handler knows about this, and should acknowledge
        // receipt of a breakpoint in this class
        // After this is complete, `JDWP_WORKER_THREADREF` is a thread we can invoke methods on over JDWP
        JdwpWorker.spinWaitForJdwpBpToSuspendWorkerThread();

        return new JdwpStaticCallable(((ClassType)refType.classObject().reflectedType()), jdwp_getThread);
    }

    private static final int SIZEOF_INSTR_INVOKE_INTERFACE = 5;
    
    public LuceeVm(Config config, VirtualMachine vm) {
        this.config_ = config;
        this.vm_ = vm;
        
        initEventPump();

        jdwp_getThread = bootThreadWorker();

        bootClassTracking();

        bootThreadTracking();

        GlobalIDebugManagerHolder.debugManager.registerCfStepHandler((thread, minDistanceToLuceedebugBaseFrame) -> {
            final var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);
            final var done = new AtomicBoolean(false);
            
            //
            // Have to do this on a seperate thread in order to suspend the supplied thread,
            // which by current design will always be the current thread.
            //
            // Weird, we take a `thread` argument, but we always have the caller passing in its current thread.
            // And the caller's current thread is the same as our current thread.
            //
            // Maybe it is good that we support either / or
            // (i.e. the passed in thread may or may not be the current thread, we should 'just work' in either case)
            //
            CompletableFuture.runAsync(() -> {
                try {
                    threadRef.suspend();
                    
                    /**
                     * Start the search for the "step notification entry frame" from `minDistanceToLuceedebugBaseFrame`,
                     * which is the count of "frames we've definitely passed through to get to that point on the thread".
                     * We can't know exactly how many frames because of at least the non-determinism of whether the target
                     * thread has entered into AtomicBoolean.get() prior to being suspended.
                     * 
                     * The stack on the target thread looks something like this:
                     * 
                     * 1) AtomicBoolean.get() // might be on stack, might not be yet; either way, the target thread is suspended
                     * 2) IDebugManager.CfStepCallback.call() // the outer lambda here
                     * 3) <...various DebugManager frames...>
                     * 4) DebugManager step handler frame
                     * 5) topmost lucee frame on an InvokeInterface instruction, getting us into DebugManager step handler
                     * 6) <...various Lucee frames...>
                     * 
                     * We want to scan until we find (4). Once we've found (4), the frame below it is guaranteed to be the topmost lucee frame
                     * that we want to return to. We can't "just" scan for the topmost lucee frame because we don't know its name or
                     * really anything about it. Our contract with ourselves is that the method call from (5) into (4) is an InvokeInterface
                     * instruction, and with that we can know which bytecode index to set our next breakpoint at.
                     * 
                     * We loop until `Integer.MAX_VALUE`, but, really that means "until all frames have been iterated over".
                     * We should __always__ be able to find the target frame here.
                     *  - We should find the target frame after only a few (1 or 2) iterations
                     *  - If we don't find it in the first few, we'll iterate through the whole stack, and once we do `threadRef.frame(X)`
                     *    where X is larger than the number of frames on the stack, we'll get an exception.
                     */
                    for (int i = minDistanceToLuceedebugBaseFrame; i < Integer.MAX_VALUE; i++) {
                        if (IDebugManager.isStepNotificationEntryFunc(threadRef.frame(i).location().method().name())) {
                            var stepInvokingCfFrame = threadRef.frame(i+1);
                            var location = stepInvokingCfFrame
                                .location()
                                .method()
                                .locationOfCodeIndex(
                                    // frame is executing an invokeInterface instruction;
                                    // set the next breakpoint exactly after this instruction.
                                    stepInvokingCfFrame
                                        .location()
                                        .codeIndex() + SIZEOF_INSTR_INVOKE_INTERFACE
                                );
                            
                            final var bp = vm_.eventRequestManager().createBreakpointRequest(location);
                            bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                            bp.addThreadFilter(threadRef);
                            bp.addCountFilter(1);
                            bp.setEnabled(true);
                            
                            steppingStatesByThread.put(
                                JdwpThreadID.of(threadRef),
                                SteppingState.finalizingViaAwaitedBreakpoint
                            ); // races with step handlers ?
    
                            done.set(true);
                            continue_(threadRef);
                            return;
                        }
                        else {
                            continue;
                        }
                    }

                    // We'll either have found the target frame and did the work and returned,
                    // or asked for one frame past the last frame which will have thrown an exception.
                    throw new RuntimeException("unreachable");
                }
                catch (Throwable e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }, stepHandlerExecutor);

            // We might spin a little here, but the majority of the wait
            // will be conducted while this thread is suspended.
            // We'll have set done=true prior to resuming this thread.
            while (!done.get()); // about ~8ms to queueWork + wait for work to complete
        });
    }

    /**
     * Our steps are different than jdwp step events. We don't use jdwp step events because
     * they tend to (always? not sure) drop the jvm into interpreted mode, with associated perf degredation.
     * `isStepping=true` means "the next breakpoint we hit actually implies a completed DAP step event, rather
     * than a user-defined breakpoint event"
     */
    private static enum SteppingState { stepping, finalizingViaAwaitedBreakpoint }
    private ConcurrentMap<JdwpThreadID, SteppingState> steppingStatesByThread = new ConcurrentHashMap<>();
    private Consumer<JdwpThreadID> stepEventCallback = null;
    private BiConsumer<JdwpThreadID, DapBreakpointID> breakpointEventCallback = null;
    private Consumer<BreakpointsChangedEvent> breakpointsChangedCallback = null;

    public void registerStepEventCallback(Consumer<JdwpThreadID> cb) {
        stepEventCallback = cb;
    }

    public void registerBreakpointEventCallback(BiConsumer<JdwpThreadID, DapBreakpointID> cb) {
        breakpointEventCallback = cb;
    }

    public void registerBreakpointsChangedCallback(Consumer<BreakpointsChangedEvent> cb) {
        this.breakpointsChangedCallback = cb;
    }

    private void initEventPump() {
        new java.lang.Thread(() -> {
            try {
                while (true) {
                    var eventSet = vm_.eventQueue().remove();
                    for (var event : eventSet) {
                        if (event instanceof ThreadStartEvent) {
                            handleThreadStartEvent((ThreadStartEvent) event);
                        }
                        else if (event instanceof ThreadDeathEvent) {
                            handleThreadDeathEvent((ThreadDeathEvent) event);
                        }
                        else if (event instanceof ClassPrepareEvent) {
                            handleClassPrepareEvent((ClassPrepareEvent) event);
                        }
                        else if (event instanceof BreakpointEvent) {
                            handleBreakpointEvent((BreakpointEvent) event);
                        }
                        else {
                            System.out.println("Unexpected jdwp event " + event);
                            System.exit(1);
                        }
                    }
                }
            }
            catch (InterruptedException e) {
                // Maybe we want to handle this differently?
                e.printStackTrace();
                System.exit(1);
            }
            catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }
        }).start();
    }

    private void initCurrentThreadListing() {
        for (var threadRef : vm_.allThreads()) {
            trackThreadReference(threadRef);
        }
    }

    /**
     * this must be jdwp event handler safe (i.e. not deadlock the event handler)
     */
    private void trackThreadReference(ThreadReference threadRef) {
        try {
            final List<? extends Value> args = Arrays.asList(threadRef);
            final var v = (LongValue) jdwp_getThread.classType.invokeMethod(
                JDWP_WORKER_THREADREF,
                jdwp_getThread.method,
                args,
                ObjectReference.INVOKE_SINGLE_THREADED
            );

            final long key = v.value();
            final Thread thread = JdwpWorker.jdwp_getThreadResult(key);
            threadMap_.register(thread, threadRef);
        }
        catch (ObjectCollectedException e) {
            if (JDWP_WORKER_THREADREF.isCollected()) {
                // this should never be collected
                System.out.println("[luceedebug] fatal: JDWP_WORKER_THREADREF is collected");
                System.exit(1);
            }
            else {
                // discard, can't track a thread that got collected
            }
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * this must be jdwp event handler safe (i.e. not deadlock the event handler)
     */
    private void untrackThreadReference(ThreadReference threadRef) {
        threadMap_.unregister(threadRef);
    }

    /**
     * this must be jdwp event handler safe (i.e. not deadlock the event handler)
     */
    private void trackClassRef(ReferenceType refType) {
        try {
            final var maybeNull_klassMap = KlassMap.maybeNull_tryBuildKlassMap(config_, refType);
            
            if (maybeNull_klassMap == null) {
                // try to get a meaningful name; but default to normal "toString" in the exceptional case
                String name = refType.toString();
                try {
                    name = refType.sourceName();
                }
                catch (Throwable e) {
                    // discard
                }

                if (name.contains("lucee.commons.lang.MemoryClassLoader")) {
                    //
                    // Suppress logging for names like "class 1cs8o747dipwu (loaded by instance of lucee.commons.lang.MemoryClassLoader(id=2152))"
                    //
                    // Typically this means "ephemeral class loaded as part of an IDE expression eval request"
                    // so it's not important to indicate we couldn't do anything with it
                    //
                }
                else {
                    System.out.println("[luceedebug] class information for reftype " + name + " could not be retrieved.");
                }
                return;
            }

            final var klassMap = maybeNull_klassMap; // definitely non-null

            Set<ReplayableCfBreakpointRequest> replayableBreakpointRequests = replayableBreakpointRequestsByAbsPath_.get(klassMap.sourceName);

            klassMap_
                .computeIfAbsent(klassMap.sourceName, _z -> new HashSet<>())
                .add(klassMap);

            if (replayableBreakpointRequests != null) {
                rebindBreakpoints(klassMap.sourceName, replayableBreakpointRequests);
            }
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void handleThreadStartEvent(ThreadStartEvent event) {
        trackThreadReference(event.thread());
    }

    private void handleThreadDeathEvent(ThreadDeathEvent event) {
        untrackThreadReference(event.thread());
    }

    private void handleClassPrepareEvent(ClassPrepareEvent event) {
        if (event.referenceType().name().equals("lucee.runtime.Page")) {
            // This can happen exactly once
            // Once we get the reftype, we create the tracking request to
            // track class prepare events for all subtypes of lucee.runtime.Page
            vm_.eventRequestManager().deleteEventRequest(event.request());
            bootClassTracking(event.referenceType());
            // We are required to have suspended the thread,
            // otherwise we may have just missed a bunch of subtypes of lucee.runtime.Page getting classPrepare'd.
            // Now that we have registered our request to track those class types, we can keep going.
            event.thread().resume();
        }
        else {
            trackClassRef(event.referenceType());
            // we will have suspended this thread,
            // in order to bind breakpoints synchronously with respect to the class's loading
            event.thread().resume();
        }
    }

    private void handleBreakpointEvent(BreakpointEvent event) {
        // worker initialization, should only happen once per jvm instance
        if (event.location().declaringType().classObject().uniqueID() == JDWP_WORKER_CLASS_ID) {
            JDWP_WORKER_THREADREF = event.thread();
            JdwpWorker.notifyJdwpSuspendedWorkerThread();
            return;
        }

        final var threadID = JdwpThreadID.of(event.thread());

        suspendedThreads.add(threadID);

        if (steppingStatesByThread.remove(threadID, SteppingState.finalizingViaAwaitedBreakpoint)) {
            // We're stepping, and we completed a step; now, we hit the breakpoint
            // that the step-completition handler installed. Stepping is complete.
            if (stepEventCallback != null) {
                // We would delete the breakpoint request here,
                // but it should have been registered with an eventcount filter of 1,
                // meaning that it has auto-expired
                stepEventCallback.accept(JdwpThreadID.of(event.thread()));
            }
        }
        else {
            // if we are stepping, but we hit a breakpoint, cancel the stepping
            if (steppingStatesByThread.remove(threadID, SteppingState.stepping)) {
                GlobalIDebugManagerHolder.debugManager.clearStepRequest(threadMap_.getThreadByJdwpIdOrFail(threadID));
            }

            final EventRequest request = event.request();
            final Object maybe_expr = request.getProperty(LUCEEDEBUG_BREAKPOINT_EXPR);
            if (maybe_expr instanceof String) {
                // if we have a conditional expr bound to this breakpoint, try to evaluate in the context of the topmost cf frame for this thread
                // if it's not cf-truthy, then unsuspend this thread
                final var jdwp_threadID = JdwpThreadID.of(event.thread());
                if (!GlobalIDebugManagerHolder.debugManager.evaluateAsBooleanForConditionalBreakpoint(
                    threadMap_.getThreadByJdwpIdOrFail(jdwp_threadID),
                    (String)maybe_expr)
                ) {
                    continue_(jdwp_threadID);
                    return;
                }
            }

            if (breakpointEventCallback != null) {
                final var bpID = (DapBreakpointID) request.getProperty(LUCEEDEBUG_BREAKPOINT_ID);
                breakpointEventCallback.accept(threadID, bpID);
            }
        }
    }

    public ThreadReference[] getThreadListing() {
        var result = new ArrayList<ThreadReference>();
        for (var threadRef : threadMap_.threadRefByThread.values()) {
            result.add(threadRef);
        }

        return result.toArray(size -> new ThreadReference[size]);
    }

    public IDebugFrame[] getStackTrace(long jdwpThreadId) {
        var thread = threadMap_.getThreadByJdwpIdOrFail(new JdwpThreadID(jdwpThreadId));
        return GlobalIDebugManagerHolder.debugManager.getCfStack(thread);
    }

    public IDebugEntity[] getScopes(long frameID) {
        return GlobalIDebugManagerHolder.debugManager.getScopesForFrame(frameID);
    }

    public IDebugEntity[] getVariables(long ID) {
        return GlobalIDebugManagerHolder.debugManager.getVariables(ID, null);
    }

    public IDebugEntity[] getNamedVariables(long ID) {
        return GlobalIDebugManagerHolder.debugManager.getVariables(ID, IDebugEntity.DebugEntityType.NAMED);
    }

    public IDebugEntity[] getIndexedVariables(long ID) {
        return GlobalIDebugManagerHolder.debugManager.getVariables(ID, IDebugEntity.DebugEntityType.INDEXED);
    }

    private AtomicInteger breakpointID = new AtomicInteger();
    private DapBreakpointID nextDapBreakpointID() {
        return new DapBreakpointID(breakpointID.incrementAndGet());
    }

    public void rebindBreakpoints(CanonicalServerAbsPath serverAbsPath, Collection<ReplayableCfBreakpointRequest> cfBpRequests) {
        var changedBreakpoints = __internal__bindBreakpoints(serverAbsPath, ReplayableCfBreakpointRequest.getLineInfo(cfBpRequests));

        if (breakpointsChangedCallback != null) {
            breakpointsChangedCallback.accept(BreakpointsChangedEvent.justChanges(changedBreakpoints));
        }
    }   

    static class BpLineAndId {
        final RawIdePath ideAbsPath;
        final CanonicalServerAbsPath serverAbsPath;
        final int line;
        final DapBreakpointID id;
        final String expr;

        public BpLineAndId(RawIdePath ideAbsPath, CanonicalServerAbsPath serverAbsPath, int line, DapBreakpointID id, String expr) {
            this.ideAbsPath = ideAbsPath;
            this.serverAbsPath = serverAbsPath;
            this.line = line;
            this.id = id;
            this.expr = expr;
        }
    }

    private BpLineAndId[] freshBpLineAndIdRecordsFromLines(RawIdePath idePath, CanonicalServerAbsPath serverPath, int[] lines, String[] exprs) {
        if (lines.length != exprs.length) { // really this should be some kind of aggregate
            throw new AssertionError("lines.length != exprs.length");
        }

        var result = new BpLineAndId[lines.length];

        Set<ReplayableCfBreakpointRequest> bpInfo = replayableBreakpointRequestsByAbsPath_.get(serverPath);

        for (var i = 0; i < lines.length; ++i) {
            final int line = lines[i];

            DapBreakpointID id = iife(() -> {
                if (bpInfo == null) {
                    return nextDapBreakpointID();
                }
                for (var z : bpInfo) {
                    if (z.line == line) {
                        return z.id;
                    }
                }
                return nextDapBreakpointID();
            });

            result[i] = new BpLineAndId(idePath, serverPath, line, id, exprs[i]);
        }
        return result;
    }

    public IBreakpoint[] bindBreakpoints(RawIdePath idePath, CanonicalServerAbsPath serverPath, int[] lines, String[] exprs) {
        return __internal__bindBreakpoints(serverPath, freshBpLineAndIdRecordsFromLines(idePath, serverPath, lines, exprs));
    }

    /**
     * caller is responsible for transforming the source path into a cf path,
     * i.e. the IDE might say "/foo/bar/baz.cfc" but we are only aware of "/app-host-container/foo/bar/baz.cfc" or etc. 
     */
    private IBreakpoint[] __internal__bindBreakpoints(CanonicalServerAbsPath serverAbsPath, BpLineAndId[] lineInfo) {
        final Set<KlassMap> klassMapSet = klassMap_.get(serverAbsPath);

        if (klassMapSet == null) {
            var replayable = replayableBreakpointRequestsByAbsPath_.computeIfAbsent(serverAbsPath, _z -> new HashSet<>());

            IBreakpoint[] result = new Breakpoint[lineInfo.length];
            for (int i = 0; i < lineInfo.length; i++) {
                final var ideAbsPath = lineInfo[i].ideAbsPath;
                final var shadow_serverAbsPath = lineInfo[i].serverAbsPath; // should be same as first arg to this method, kind of redundant
                final var line = lineInfo[i].line;
                final var id = lineInfo[i].id;
                final var expr = lineInfo[i].expr;

                result[i] = Breakpoint.Unbound(line, id);
                replayable.add(new ReplayableCfBreakpointRequest(ideAbsPath, shadow_serverAbsPath, line, id, expr));
            }

            return result;
        }

        IBreakpoint[] bpListPerMapping = new IBreakpoint[0];

        clearExistingBreakpoints(serverAbsPath);

        List<KlassMap> garbageCollectedKlassMaps = new ArrayList<>();

        for (KlassMap mapping : klassMapSet) {
            if (mapping.isCollected()) {
                // This still leaves us with a little race where it gets collected after this,
                // but before we start adding breakpoints to the gc'd class.
                garbageCollectedKlassMaps.add(mapping);
                continue;
            }

            try {
                bpListPerMapping = __internal__idempotentBindBreakpoints(mapping, lineInfo);
            }
            catch (ObjectCollectedException e) {
                garbageCollectedKlassMaps.add(mapping);
            }
        }

        garbageCollectedKlassMaps.forEach(klassMap -> {
            Set<ReplayableCfBreakpointRequest> z = replayableBreakpointRequestsByAbsPath_.get(klassMap.sourceName);
            if (z != null) {
                z.removeIf(bpReq -> bpReq.serverAbsPath.equals(klassMap.sourceName));
            }

            klassMapSet.remove(klassMap);
        });

        // return just the last one
        return bpListPerMapping;
    }

    

    /**
     * Seems we're not allowed to inspect the jdwp-native id, but we can attach our own
     */
    private IBreakpoint[] __internal__idempotentBindBreakpoints(KlassMap klassMap, BpLineAndId[] lineInfo) {
        final var replayable = replayableBreakpointRequestsByAbsPath_.computeIfAbsent(klassMap.sourceName, _z -> new HashSet<>());
        final var result = new ArrayList<IBreakpoint>();

        for (int i = 0; i < lineInfo.length; ++i) {
            final var ideAbsPath = lineInfo[i].ideAbsPath;
            final var serverAbsPath = lineInfo[i].serverAbsPath;
            final var line = lineInfo[i].line;
            final var id = lineInfo[i].id;
            final var maybeNull_location = klassMap.lineMap.get(line);
            final var expr = lineInfo[i].expr;

            if (maybeNull_location == null) {
                replayable.add(new ReplayableCfBreakpointRequest(ideAbsPath, serverAbsPath, line, id, expr));
                result.add(Breakpoint.Unbound(line, id));
            }
            else {
                final var bpRequest = vm_.eventRequestManager().createBreakpointRequest(maybeNull_location);
                bpRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                bpRequest.putProperty(LUCEEDEBUG_BREAKPOINT_ID, id);

                if (expr != null) {
                    bpRequest.putProperty(LUCEEDEBUG_BREAKPOINT_EXPR, expr);
                }

                bpRequest.setEnabled(true);
                replayable.add(new ReplayableCfBreakpointRequest(ideAbsPath, serverAbsPath, line, id, expr, bpRequest));
                result.add(Breakpoint.Bound(line, id));
            }
        }

        replayableBreakpointRequestsByAbsPath_.put(klassMap.sourceName, replayable);

        return result.toArray(size -> new IBreakpoint[size]);
    }

    /**
     * returns an array of the line numbers the old breakpoints were bound to
     */
    private void clearExistingBreakpoints(CanonicalServerAbsPath absPath) {
        Set<ReplayableCfBreakpointRequest> replayable = replayableBreakpointRequestsByAbsPath_.get(absPath);

        // "just do it" in all cases
        replayableBreakpointRequestsByAbsPath_.remove(absPath);

        if (replayable == null) {
            // no existing bp requests for the class having this source path
            return;
        }

        var bpRequests = ReplayableCfBreakpointRequest.getJdwpRequests(replayable);
        final var result = new int[bpRequests.size()];

        for (int i = 0; i < result.length; ++i) {
            result[i] = bpRequests.get(i).location().lineNumber();
        }

        vm_.eventRequestManager().deleteEventRequests(bpRequests);
    }

    public void clearAllBreakpoints() {
        replayableBreakpointRequestsByAbsPath_.clear();
        vm_.eventRequestManager().deleteAllBreakpoints();
    }

    /**
     * Non-concurrent map is OK here?
     * reasoning: all requests come from the IDE, and there is only one connected IDE, communicating over a single socket.
     */
    private HashSet<JdwpThreadID> suspendedThreads = new HashSet<>();

    public void continue_(JdwpThreadID jdwpThreadID) {
        final var threadRef = threadMap_.getThreadRefByJdwpIdOrFail(jdwpThreadID);
        continue_(threadRef);
    }

    private void continue_(ThreadReference threadRef) {
        // Maybe a race here -- order of these ops matters?
        // Our tracking info is slightly out of sync with the realworld here,
        // if we remove the entry from suspended threads and then call resume.
        // But the same problem exists if we call resume, and then remove it from suspended threads ... ?
        suspendedThreads.remove(JdwpThreadID.of(threadRef));

        /**
         * Make a copy of "current suspend count", rather than loop by testing `threadRef.suspendCount()`
         * Otherwise, we race with the resumed thread and
         * breakpoints that might get hit immediately after resuming:
         *  - call thread.resume()
         *  - suspendCount() hits 0 --> target thread resumes
         *  - Before we restart the loop, a breakpoint on the resumed thread is hit
         *  - retest loop predicate, checking against `threadRef.suspendCount()` shows that is non-zero
         *  - We `threadRef.resume()` again, effectively skipping the breakpoint the earlier resume allowed us to hit
         */
        var suspendCount = threadRef.suspendCount();

        while (suspendCount > 0) {
            threadRef.resume();
            suspendCount--;
        }
    }

    public void continueAll() {
        // avoid concurrent modification exceptions, calling continue_ mutates `suspendedThreads`
        Arrays
            // TODO: Set<T>.toArray(sz -> new T[sz]) is not typesafe, changing the type of Set<T>
            // doesn't flow through into the toArray call. Is there a more idiomatic, typesafe way to do
            // this?
            .asList(suspendedThreads.toArray(size -> new JdwpThreadID[size]))
            .forEach(jdwpThreadID -> continue_(jdwpThreadID));
    }

    public void stepOut(long jdwpThreadID) {
        stepOut(new JdwpThreadID(jdwpThreadID));
    }

    public void stepOver(long jdwpThreadID) {
        stepOver(new JdwpThreadID(jdwpThreadID));
    }

    public void stepIn(long jdwpThreadID) {
        stepIn(new JdwpThreadID(jdwpThreadID));
    }

    public void continue_(long jdwpThreadID) {
        continue_(new JdwpThreadID(jdwpThreadID));
    }

    public void stepIn(JdwpThreadID jdwpThreadID) {
        if (steppingStatesByThread.containsKey(jdwpThreadID)) {
            return;
        }

        steppingStatesByThread.put(jdwpThreadID, SteppingState.stepping);

        var thread = threadMap_.getThreadByJdwpIdOrFail(jdwpThreadID);
        var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);

        if (threadRef.suspendCount() == 0) {
            System.out.println("step in handler expected thread " + thread + " to already be suspended, but suspendCount was 0.");
            System.exit(1);
            return;
        }

        GlobalIDebugManagerHolder.debugManager.registerStepRequest(thread, DebugManager.CfStepRequest.STEP_INTO);

        continue_(threadRef);
    }

    public void stepOver(JdwpThreadID jdwpThreadID) {
        if (steppingStatesByThread.containsKey(jdwpThreadID)) {
            return;
        }

        steppingStatesByThread.put(jdwpThreadID, SteppingState.stepping);

        var thread = threadMap_.getThreadByJdwpIdOrFail(jdwpThreadID);
        var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);
        
        if (threadRef.suspendCount() == 0) {
            System.out.println("step over handler expected thread " + thread + " to already be suspended, but suspendCount was 0.");
            System.exit(1);
            return;
        }

        GlobalIDebugManagerHolder.debugManager.registerStepRequest(thread, DebugManager.CfStepRequest.STEP_OVER);
        
        continue_(threadRef);
    }

    private void stepOut(JdwpThreadID jdwpThreadID) {
        if (steppingStatesByThread.containsKey(jdwpThreadID)) {
            return;
        }

        steppingStatesByThread.put(jdwpThreadID, SteppingState.stepping);

        var thread = threadMap_.getThreadByJdwpIdOrFail(jdwpThreadID);
        var threadRef = threadMap_.getThreadRefByThreadOrFail(thread);

        if (threadRef.suspendCount() == 0) {
            System.out.println("step out handler expected thread " + thread + " to already be suspended, but suspendCount was 0.");
            System.exit(1);
            return;
        }

        GlobalIDebugManagerHolder.debugManager.registerStepRequest(thread, DebugManager.CfStepRequest.STEP_OUT);
        
        continue_(threadRef);
    }

    // presumably, the requester is requesting to dump a variable because they
    // have at least one suspended thread they're investigating. We should have that thread,
    // or at least one suspended thread. It doesn't matter which thread we use, we just
    // need there to be an associated PageContext, so we can get its:
    //   - Config
    //   - ServletConfig
    // If we can figure out how to get those from some singleton somewhere then we wouldn't need
    // to do any thread lookup here.
    //
    // There's no guarantee that a suspended thread is associated with a PageContext,
    // so we need to pass a list of all suspended threads, and the manager can use that
    // to find a PageContext.
    //
    private ArrayList<Thread> getSuspendedThreadListForDumpWorker() {
        final var suspendedThreadsList = new ArrayList<Thread>();
        suspendedThreads.iterator().forEachRemaining(jdwpThreadID -> {
            var thread = threadMap_.getThreadByJdwpId(jdwpThreadID);
            if (thread != null) {
                suspendedThreadsList.add(thread);
            }
        });
        return suspendedThreadsList;
    }

    public String dump(int dapVariablesReference) {
        return GlobalIDebugManagerHolder.debugManager.doDump(getSuspendedThreadListForDumpWorker(), dapVariablesReference);
    }

    public String dumpAsJSON(int dapVariablesReference) {
        return GlobalIDebugManagerHolder.debugManager.doDumpAsJSON(getSuspendedThreadListForDumpWorker(), dapVariablesReference);
    }

    public String[] getTrackedCanonicalFileNames() {
        final var result = new ArrayList<String>();
        for (var klassMap : klassMap_.values()) {
            for (var mapping : klassMap) {
                result.add(mapping.sourceName.get());
            }
        }
        return result.toArray(size -> new String[size]);
    }

    public String[][] getBreakpointDetail() {
        final var result = new ArrayList<ArrayList<String>>();
        for (var bps : replayableBreakpointRequestsByAbsPath_.entrySet()) {
            for (var bp : bps.getValue()) {
                final var commonSuffix = ":" + bp.line + (bp.maybeNull_jdwpBreakpointRequest == null ? " (unbound)" : " (bound)");
                final var pair = new ArrayList<String>();
                pair.add(bp.ideAbsPath + commonSuffix);
                pair.add(bp.serverAbsPath + commonSuffix);
                result.add(pair);
            }
        }
        return result.stream().map(u -> u.toArray(new String[0])).toArray(String[][]::new);
    }

    public String getSourcePathForVariablesRef(int variablesRef) {
        return GlobalIDebugManagerHolder.debugManager.getSourcePathForVariablesRef(variablesRef);
    }

    public Either<String, Either<ICfValueDebuggerBridge, String>> evaluate(int frameID, String expr) {
        return GlobalIDebugManagerHolder.debugManager.evaluate((Long)(long)frameID, expr);
    }
}
