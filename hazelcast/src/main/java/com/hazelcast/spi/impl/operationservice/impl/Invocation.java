/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.spi.impl.operationservice.impl;

import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.core.OperationTimeoutException;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.partition.InternalPartition;
import com.hazelcast.spi.Callback;
import com.hazelcast.spi.ExceptionAction;
import com.hazelcast.spi.ExecutionService;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.ResponseHandler;
import com.hazelcast.spi.WaitSupport;
import com.hazelcast.spi.exception.ResponseAlreadySentException;
import com.hazelcast.spi.exception.RetryableException;
import com.hazelcast.spi.exception.RetryableIOException;
import com.hazelcast.spi.exception.TargetNotMemberException;
import com.hazelcast.spi.exception.WrongTargetException;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.operationexecutor.OperationExecutor;
import com.hazelcast.spi.impl.operationservice.impl.responses.CallTimeoutResponse;
import com.hazelcast.spi.impl.operationservice.impl.responses.ErrorResponse;
import com.hazelcast.spi.impl.operationservice.impl.responses.NormalResponse;
import com.hazelcast.util.Clock;
import com.hazelcast.util.ExceptionUtil;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static com.hazelcast.spi.ExceptionAction.CONTINUE_WAIT;
import static com.hazelcast.spi.ExceptionAction.RETRY_INVOCATION;
import static com.hazelcast.spi.OperationAccessor.isJoinOperation;
import static com.hazelcast.spi.OperationAccessor.isMigrationOperation;
import static com.hazelcast.spi.OperationAccessor.setCallTimeout;
import static com.hazelcast.spi.OperationAccessor.setCallerAddress;
import static com.hazelcast.spi.OperationAccessor.setInvocationTime;
import static com.hazelcast.spi.impl.operationservice.impl.InternalResponse.INTERRUPTED_RESPONSE;
import static com.hazelcast.spi.impl.operationservice.impl.InternalResponse.NULL_RESPONSE;
import static com.hazelcast.spi.impl.operationservice.impl.InternalResponse.WAIT_RESPONSE;

/**
 * The Invocation evaluates a Operation invocation.
 * <p/>
 * Using the InvocationFuture, one can wait for the completion of a Invocation.
 */
abstract class Invocation implements ResponseHandler, Runnable {

    private static final AtomicReferenceFieldUpdater<Invocation, Boolean> RESPONSE_RECEIVED_FIELD_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(Invocation.class, Boolean.class, "responseReceived");

    private static final AtomicIntegerFieldUpdater<Invocation> BACKUPS_COMPLETED_FIELD_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(Invocation.class, "backupsCompleted");


    private static final long MIN_TIMEOUT = 10000;
    private static final int MAX_FAST_INVOCATION_COUNT = 5;
    //some constants for logging purposes
    private static final int LOG_MAX_INVOCATION_COUNT = 99;
    private static final int LOG_INVOCATION_COUNT_MOD = 10;

    /**
     * The time in millis when the response of the primary has been received.
     */
    protected long pendingResponseReceivedMillis = -1;
    protected final long callTimeout;
    protected final NodeEngineImpl nodeEngine;
    protected final String serviceName;
    protected final Operation op;
    protected final int partitionId;
    protected final int replicaIndex;
    protected final int tryCount;
    protected final long tryPauseMillis;
    protected final ILogger logger;
    final boolean resultDeserialized;
    boolean remote;

    volatile Object pendingResponse;
    volatile int backupsExpected;
    volatile int backupsCompleted;
    Address invTarget;
    MemberImpl invTargetMember;

    private final InvocationFuture invocationFuture;
    private final OperationServiceImpl operationService;

    //needs to be a Boolean because it is updated through the RESPONSE_RECEIVED_FIELD_UPDATER
    private volatile Boolean responseReceived = Boolean.FALSE;

    //writes to that are normally handled through the INVOKE_COUNT_UPDATER to ensure atomic increments / decrements
    private volatile int invokeCount;

    Invocation(NodeEngineImpl nodeEngine, String serviceName, Operation op, int partitionId,
               int replicaIndex, int tryCount, long tryPauseMillis, long callTimeout, Callback<Object> callback,
               boolean resultDeserialized) {
        this.operationService = (OperationServiceImpl) nodeEngine.getOperationService();
        this.logger = operationService.invocationLogger;
        this.nodeEngine = nodeEngine;
        this.serviceName = serviceName;
        this.op = op;
        this.partitionId = partitionId;
        this.replicaIndex = replicaIndex;
        this.tryCount = tryCount;
        this.tryPauseMillis = tryPauseMillis;
        this.callTimeout = getCallTimeout(callTimeout);
        this.invocationFuture = new InvocationFuture(operationService, this, callback);
        this.resultDeserialized = resultDeserialized;
    }

    abstract ExceptionAction onException(Throwable t);

    public String getServiceName() {
        return serviceName;
    }

    InternalPartition getPartition() {
        return nodeEngine.getPartitionService().getPartition(partitionId);
    }

    private long getCallTimeout(long callTimeout) {
        if (callTimeout > 0) {
            return callTimeout;
        }

        final long defaultCallTimeout = operationService.getDefaultCallTimeoutMillis();
        if (op instanceof WaitSupport) {
            final long waitTimeoutMillis = op.getWaitTimeout();
            if (waitTimeoutMillis > 0 && waitTimeoutMillis < Long.MAX_VALUE) {
                /*
                 * final long minTimeout = Math.min(defaultCallTimeout, MIN_TIMEOUT);
                 * long callTimeout = Math.min(waitTimeoutMillis, defaultCallTimeout);
                 * callTimeout = Math.max(a, minTimeout);
                 * return callTimeout;
                 *
                 * Below two lines are shortened version of above*
                 * using min(max(x,y),z)=max(min(x,z),min(y,z))
                 */
                final long max = Math.max(waitTimeoutMillis, MIN_TIMEOUT);
                return Math.min(max, defaultCallTimeout);
            }
        }
        return defaultCallTimeout;
    }


    private void invokeInternal(boolean isAsync) {
        if (invokeCount > 0) {
            // no need to be pessimistic.
            throw new IllegalStateException("An invocation can not be invoked more than once!");
        }

        if (op.getCallId() != 0) {
            throw new IllegalStateException("An operation[" + op + "] can not be used for multiple invocations!");
        }

        try {
            setCallTimeout(op, callTimeout);
            setCallerAddress(op, nodeEngine.getThisAddress());
            op.setNodeEngine(nodeEngine)
                    .setServiceName(serviceName)
                    .setPartitionId(partitionId)
                    .setReplicaIndex(replicaIndex);

            boolean isAllowed = operationService.operationExecutor.isInvocationAllowedFromCurrentThread(op, isAsync);
            if (!isAllowed && !isMigrationOperation(op)) {
                throw new IllegalThreadStateException(Thread.currentThread() + " cannot make remote call: " + op);
            }
            doInvoke();
        } catch (Exception e) {
            handleInvocationException(e);
        }
    }

    public final InvocationFuture invoke() {
        invokeInternal(false);
        return invocationFuture;
    }

    public final void invokeAsync() {
        invokeInternal(true);
    }

    private void handleInvocationException(Exception e) {
        if (e instanceof RetryableException) {
            notify(e);
        } else {
            throw ExceptionUtil.rethrow(e);
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "VO_VOLATILE_INCREMENT",
            justification = "We have the guarantee that only a single thread at any given time can change the volatile field")
    private void doInvoke() {
        if (!engineActive()) {
            return;
        }

        invokeCount++;

        if (!initInvocationTarget()) {
            return;
        }

        setInvocationTime(op, nodeEngine.getClusterService().getClusterClock().getClusterTime());
        operationService.invocationsRegistry.register(this);
        if (remote) {
            doInvokeRemote();
        } else {
            doInvokeLocal();
        }
    }

    private void doInvokeLocal() {
        if (op.getCallerUuid() == null) {
            op.setCallerUuid(nodeEngine.getLocalMember().getUuid());
        }

        responseReceived = Boolean.FALSE;
        op.setResponseHandler(this);

        OperationExecutor executor = operationService.operationExecutor;
        executor.runOnCallingThreadIfPossible(op);
    }

    private void doInvokeRemote() {
        boolean sent = operationService.send(op, invTarget);
        if (!sent) {
            operationService.invocationsRegistry.deregister(this);
            notify(new RetryableIOException("Packet not send to -> " + invTarget));
        }
    }

    private boolean engineActive() {
        if (nodeEngine.isActive()) {
            return true;
        }

        remote = false;
        notify(new HazelcastInstanceNotActiveException());
        return false;
    }

    /**
     * Initializes the invocation target.
     *
     * @return true of the initialization was a success
     */
    private boolean initInvocationTarget() {
        Address thisAddress = nodeEngine.getThisAddress();

        invTarget = getTarget();

        if (invTarget == null) {
            remote = false;
            if (nodeEngine.isActive()) {
                notify(new WrongTargetException(thisAddress, null, partitionId
                        , replicaIndex, op.getClass().getName(), serviceName));
            } else {
                notify(new HazelcastInstanceNotActiveException());
            }
            return false;
        }

        invTargetMember = nodeEngine.getClusterService().getMember(invTarget);
        if (!isJoinOperation(op) && invTargetMember == null) {
            notify(new TargetNotMemberException(invTarget, partitionId, op.getClass().getName(), serviceName));
            return false;
        }

        if (op.getPartitionId() != partitionId) {
            notify(new IllegalStateException("Partition id of operation: " + op.getPartitionId()
                    + " is not equal to the partition id of invocation: " + partitionId));
            return false;
        }

        if (op.getReplicaIndex() != replicaIndex) {
            notify(new IllegalStateException("Replica index of operation: " + op.getReplicaIndex()
                    + " is not equal to the replica index of invocation: " + replicaIndex));
            return false;
        }

        remote = !thisAddress.equals(invTarget);
        return true;
    }

    @Override
    public void sendResponse(Object obj) {
        if (!RESPONSE_RECEIVED_FIELD_UPDATER.compareAndSet(this, Boolean.FALSE, Boolean.TRUE)) {
            throw new ResponseAlreadySentException("NormalResponse already responseReceived for callback: " + this
                    + ", current-response: : " + obj);
        }
        notify(obj);
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    //this method is called by the operation service to signal the invocation that something has happened, e.g.
    //a response is returned.
    public void notify(Object response) {
        if (response == null) {
            response = NULL_RESPONSE;
        }

        if (response instanceof CallTimeoutResponse) {
            notifyCallTimeoutResponse();
            return;
        }

        if (response instanceof ErrorResponse) {
            notifyErrorResponse(((ErrorResponse) response).getCause());
            return;
        }

        if (response instanceof Throwable) {
            notifyErrorResponse((Throwable) response);
            return;
        }

        if (response instanceof NormalResponse) {
            NormalResponse normalResponse = (NormalResponse) response;
            notifyNormalResponse(normalResponse.getValue(), normalResponse.getBackupCount());
            return;
        }

        // there are no backups or the number of expected backups has returned; so signal the future that the result is ready.
        invocationFuture.set(response);
    }

    void notifyErrorResponse(Throwable error) {
        assert error != null;

        ExceptionAction action = onException(error);
        int localInvokeCount = invokeCount;
        if (action == RETRY_INVOCATION && localInvokeCount < tryCount) {
            if (localInvokeCount > LOG_MAX_INVOCATION_COUNT && localInvokeCount % LOG_INVOCATION_COUNT_MOD == 0) {
                logger.warning("Retrying invocation: " + toString() + ", Reason: " + error);
            }
            handleRetryResponse();
            return;
        }

        if (action == CONTINUE_WAIT) {
            handleWaitResponse();
            return;
        }

        notifyNormalResponse(error, 0);
    }

    void notifyNormalResponse(Object value, int expectedBackups) {
        if (value == null) {
            value = NULL_RESPONSE;
        }

        //if a regular response came and there are backups, we need to wait for the backs.
        //when the backups complete, the response will be send by the last backup or backup-timeout-handle mechanism kicks on

        if (expectedBackups > backupsCompleted) {
            // So the invocation has backups and since not all backups have completed, we need to wait.
            // It could be that backups arrive earlier than the response.

            this.pendingResponseReceivedMillis = Clock.currentTimeMillis();

            this.backupsExpected = expectedBackups;

            // It is very important that the response is set after the backupsExpected is set. Else the system
            // can assume the invocation is complete because there is a response and no backups need to respond.
            this.pendingResponse = value;

            if (backupsCompleted != expectedBackups) {
                // We are done since not all backups have completed. Therefor we should not notify the future.
                return;
            }
        }

        // We are going to notify the future that a response is available. This can happen when:
        // - we had a regular operation (so no backups we need to wait for) that completed.
        // - we had a backup-aware operation that has completed, but also all its backups have completed.
        invocationFuture.set(value);
    }

    private void handleWaitResponse() {
        invocationFuture.set(WAIT_RESPONSE);
    }

    private void handleRetryResponse() {
        if (invocationFuture.interrupted) {
            invocationFuture.set(INTERRUPTED_RESPONSE);
        } else {
            invocationFuture.set(WAIT_RESPONSE);
            final ExecutionService ex = nodeEngine.getExecutionService();
            // fast retry for the first few invocations
            if (invokeCount < MAX_FAST_INVOCATION_COUNT) {
                operationService.asyncExecutor.execute(this);
            } else {
                ex.schedule(ExecutionService.ASYNC_EXECUTOR, this, tryPauseMillis, TimeUnit.MILLISECONDS);
            }
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "VO_VOLATILE_INCREMENT",
            justification = "We have the guarantee that only a single thread at any given time can change the volatile field")
    public void notifyCallTimeoutResponse() {
        if (logger.isFinestEnabled()) {
            logger.finest("Call timed-out during wait-notify phase, retrying call: " + toString());
        }
        if (op instanceof WaitSupport) {
            // decrement wait-timeout by call-timeout
            long waitTimeout = op.getWaitTimeout();
            waitTimeout -= callTimeout;
            op.setWaitTimeout(waitTimeout);
        }
        invokeCount--;
        handleRetryResponse();
    }

    protected abstract Address getTarget();

    public void notifyOneBackupComplete() {
        final int newBackupsCompleted = BACKUPS_COMPLETED_FIELD_UPDATER.incrementAndGet(this);

        final Object pendingResponse = this.pendingResponse;
        if (pendingResponse == null) {
            // No pendingResponse has been set, so we are done since the invocation on the primary needs to complete first.
            return;
        }

        // If a pendingResponse is set, then the backupsExpected has been set. So we can now safely read backupsExpected.
        final int backupsExpected = this.backupsExpected;
        if (backupsExpected < newBackupsCompleted) {
            // the backups have not yet completed. So we are done.
            return;
        }

        if (backupsExpected != newBackupsCompleted) {
            // We managed to complete one backup, but we were not the one completing the last backup. So we are done.
            return;
        }

        // We are the lucky ones since we just managed to complete the last backup for this invocation and since the
        // pendingResponse is set, we can set it on the future.
        invocationFuture.set(pendingResponse);
    }

    public void notifyInvocationTimeout() {
        if (pendingResponse != null) {
            return;
        }

        if (invocationFuture.getWaitingThreadsCount() > 0) {
            // there are already waiting threads
            // no need to check timeout implicitly
            return;
        }

        long maxCallTimeout = invocationFuture.getMaxCallTimeout();
        if (maxCallTimeout == Long.MAX_VALUE) {
            return;
        }
        long expirationTime = op.getInvocationTime() + maxCallTimeout;
        if (expirationTime < 0) {
            // impossible to expire
            return;
        }
        if (expirationTime < Clock.currentTimeMillis()) {
            invocationFuture.set(newOperationTimeoutException(maxCallTimeout));
        }
    }

    Object newOperationTimeoutException(long totalTimeoutMs) {
        boolean hasResponse = this.pendingResponse != null;
        int backupsExpected = this.backupsExpected;
        int backupsCompleted = this.backupsCompleted;

        if (hasResponse) {
            return new OperationTimeoutException("No response for " + totalTimeoutMs + " ms."
                    + " Aborting invocation! " + toString()
                    + " Not all backups have completed! "
                    + " backups-expected:" + backupsExpected
                    + " backups-completed: " + backupsCompleted);
        } else {
            return new OperationTimeoutException("No response for " + totalTimeoutMs + " ms."
                    + " Aborting invocation! " + toString()
                    + " No response has been received! "
                    + " backups-expected:" + backupsExpected
                    + " backups-completed: " + backupsCompleted);
        }
    }

    public void checkBackupTimeout(long timeoutMillis) {
        // If the backups have completed, we are done.
        // This check also filters out all non backup-aware operations since they backupsExpected will always be equal to the
        // backupsCompleted.
        if (backupsExpected == backupsCompleted) {
            return;
        }

        long responseReceivedMillis = this.pendingResponseReceivedMillis;
        if (responseReceivedMillis == -1) {
            // no response has yet been received, so we we are done. We are only going to re-invoke an operation
            // if the response of the primary has been received, but the backups have not replied.
            return;
        }

        long expirationTime = responseReceivedMillis + timeoutMillis;
        boolean expired = expirationTime > 0 && expirationTime < Clock.currentTimeMillis();
        if (!expired) {
            // This invocation has not yet expired (so has not been in the system for a too long period) we ignore it.
            return;
        }

        boolean targetDead = nodeEngine.getClusterService().getMember(invTarget) == null;
        if (targetDead) {
            // The target doesn't exist, we are going to re-invoke this invocation. The reason why this operation is being invoked
            // is that otherwise it would be possible to loose data. In this particular case it could have happened that e.g.
            // a map.put was done, the primary has returned the response but has not yet completed the backups and then the
            // primary fails. The consequence is that the backups never will be made and the effects of the map.put, even though
            // it returned a value, will never be visible. So if we would complete the future, a response is send even though
            // its changes never made it into the system.
            resetAndReInvoke();
            return;
        }

        // The backups have not yet completed, but we are going to release the future anyway if a pendingResponse has been set.
        final Object pendingResponse = this.pendingResponse;
        if (pendingResponse != null) {
            invocationFuture.set(pendingResponse);
        }
    }

    private void resetAndReInvoke() {
        invokeCount = 0;
        pendingResponse = null;
        pendingResponseReceivedMillis = -1;
        backupsExpected = 0;
        backupsCompleted = 0;
        doInvoke();
    }

    @Override
    public void run() {
        doInvoke();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("BasicInvocation");
        sb.append("{ serviceName='").append(serviceName).append('\'');
        sb.append(", op=").append(op);
        sb.append(", partitionId=").append(partitionId);
        sb.append(", replicaIndex=").append(replicaIndex);
        sb.append(", tryCount=").append(tryCount);
        sb.append(", tryPauseMillis=").append(tryPauseMillis);
        sb.append(", invokeCount=").append(invokeCount);
        sb.append(", callTimeout=").append(callTimeout);
        sb.append(", target=").append(invTarget);
        sb.append(", backupsExpected=").append(backupsExpected);
        sb.append(", backupsCompleted=").append(backupsCompleted);
        sb.append('}');
        return sb.toString();
    }
}