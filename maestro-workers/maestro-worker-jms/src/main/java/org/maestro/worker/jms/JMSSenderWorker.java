/*
 *  Copyright 2017 Otavio R. Piske <angusyoung@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.maestro.worker.jms;

import org.maestro.common.content.ContentStrategy;
import org.maestro.common.content.ContentStrategyFactory;
import org.maestro.common.duration.EpochClocks;
import org.maestro.common.duration.EpochMicroClock;
import org.maestro.common.duration.TestDuration;
import org.maestro.common.duration.TestDurationBuilder;
import org.maestro.common.exceptions.DurationParseException;
import org.maestro.common.exceptions.MaestroException;
import org.maestro.common.jms.SenderClient;
import org.maestro.common.worker.MaestroSenderWorker;
import org.maestro.common.worker.WorkerOptions;
import org.maestro.common.worker.WorkerStateInfo;
import org.maestro.common.worker.WorkerUtils;
import org.maestro.common.writers.OneToOneWorkerChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Session;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * A sender worker for JMS-based testing
 */
public class JMSSenderWorker implements MaestroSenderWorker {
    private static final Logger logger = LoggerFactory.getLogger(JMSSenderWorker.class);

    private ContentStrategy contentStrategy;
    private TestDuration duration;
    private final OneToOneWorkerChannel workerChannel;
    private final AtomicLong messageCount = new AtomicLong(0);
    private volatile long startedEpochMillis = Long.MIN_VALUE;

    private String url;
    private long rate = 0;
    private int number;

    private final Supplier<? extends SenderClient> clientFactory;

    public JMSSenderWorker() {
        this(JMSSenderClient::new, 128 * 1024);
    }

    public JMSSenderWorker(Supplier<? extends SenderClient> clientFactory, int channelCapacity) {
        this.clientFactory = clientFactory;
        this.workerChannel = new OneToOneWorkerChannel(channelCapacity);
    }

    @Override
    public OneToOneWorkerChannel workerChannel() {
        return workerChannel;
    }

    private final WorkerStateInfo workerStateInfo = new WorkerStateInfo();

    @Override
    public long startedEpochMillis() {
        return this.startedEpochMillis;
    }

    @Override
    public WorkerStateInfo getWorkerState() {
        return workerStateInfo;
    }


    @Override
    public long messageCount() {
        return messageCount.get();
    }

    private void setMessageSize(String messageSize) {
        contentStrategy = ContentStrategyFactory.parse(messageSize);
    }

    private void setRate(String rate) {
        if (rate != null) {
            this.rate = Long.parseLong(rate);
        }
    }

    private void setBroker(String url) {
        this.url = url;
    }

    private void setDuration(String duration) {
        try {
            this.duration = TestDurationBuilder.build(duration);
        } catch (DurationParseException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void setWorkerNumber(int number) {
        this.number = number;
    }

    @Override
    public void setWorkerOptions(WorkerOptions workerOptions) {
        setRate(workerOptions.getRate());
        setDuration(workerOptions.getDuration());
        setBroker(workerOptions.getBrokerURL());
        setMessageSize(workerOptions.getMessageSize());
    }

    private static long waitNanoInterval(final long expectedFireTime, final long intervalInNanos) {
        assert intervalInNanos > 0;
        long now;
        do {
            now = System.nanoTime();
            if (now - expectedFireTime < 0) {
                LockSupport.parkNanos(expectedFireTime - now);
            }
        } while (now - expectedFireTime < 0);
        return now;
    }

    public void start() {
        startedEpochMillis = System.currentTimeMillis();
        logger.info("Starting the JMS sender worker");

        final SenderClient client = this.clientFactory.get();
        final long id = Thread.currentThread().getId();
        try {
            doClientStartup(client);

            runLoadLoop(client);

            logger.info("Worker {} completed running successfully with {} messages sent", id,
                    messageCount);
            workerStateInfo.setState(false, WorkerStateInfo.WorkerExitStatus.WORKER_EXIT_SUCCESS, null);
        } catch (InterruptedException e) {
            logger.error("JMS sender worker {} interrupted while sending messages: {}", id,
                    e.getMessage());

            workerStateInfo.setState(false, WorkerStateInfo.WorkerExitStatus.WORKER_EXIT_FAILURE, e);
        } catch (Exception e) {
            logger.error("Unable to start the sender worker: {}", e.getMessage(), e);

            workerStateInfo.setState(false, WorkerStateInfo.WorkerExitStatus.WORKER_EXIT_FAILURE, e);
        } finally {
            //the test could be considered already stopped here, but cleaning up JMS resources could take some time anyway
            client.stop();
            logger.info("Finalized worker {} after sending {} messages", id, messageCount);
        }
    }

    private void runLoadLoop(final SenderClient client) throws Exception {
        long count = 0;
        final long intervalInNanos = getIntervalInNanos();

        //it couldn't uses the Epoch in nanos because it could overflow pretty soon (less than 1 day)
        final EpochMicroClock epochMicroClock = EpochClocks.exclusiveMicro();
        final long startFireEpochMicros = epochMicroClock.microTime();
        //to avoid accumulated approx errors on the expectedSendTimeEpochMillis calculations
        long elapsedIntervalsNanos = 0;

        long nextFireTime = System.nanoTime() + intervalInNanos;
        final JmsOptions opts = ((JMSClient) client).getOpts();
        final boolean isSessionTransacted = isSessionTransacted(opts);

        while (duration.canContinue(this) && isRunning()) {
            if (intervalInNanos > 0) {
                final long now = waitNanoInterval(nextFireTime, intervalInNanos);
                assert (now - nextFireTime) >= 0 : "can't wait less than the configured interval in nanos";
                nextFireTime += intervalInNanos;
                elapsedIntervalsNanos += intervalInNanos;
            }

            final long sendTimeEpochMicros = epochMicroClock.microTime();
            final long expectedSendTimeEpochMicros;

            if (intervalInNanos > 0) {
                final long elapsedIntervalsMicros = (elapsedIntervalsNanos / 1_000L);
                expectedSendTimeEpochMicros = startFireEpochMicros + elapsedIntervalsMicros;
            } else {
                expectedSendTimeEpochMicros = sendTimeEpochMicros;
            }

            client.sendMessages(sendTimeEpochMicros, commitTransaction(count, opts, isSessionTransacted));
            workerChannel.emitRate(expectedSendTimeEpochMicros, sendTimeEpochMicros);
            count++;
            //update message sent count
            this.messageCount.lazySet(count);
        }
    }

    private boolean commitTransaction(long count, JmsOptions opts, boolean isSessionTransacted) {
        return isSessionTransacted && count % opts.getBatchAcknowledge() == 0;
    }

    private boolean isSessionTransacted(JmsOptions opts) {
        return opts.getSessionMode() == Session.SESSION_TRANSACTED && opts.getBatchAcknowledge() > 0;
    }

    private void doClientStartup(final SenderClient client) throws Exception {
        if (contentStrategy == null) {
            throw new MaestroException("Trying to run a test without defining the message size");
        }

        client.setUrl(url);
        client.setContentStrategy(contentStrategy);

        workerStateInfo.setState(true, null, null);
        client.setNumber(number);
        client.start();
    }

    private long getIntervalInNanos() {
        final long intervalInNanos = WorkerUtils.getExchangeInterval(this.rate);

        if (intervalInNanos > 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("JMS Sender Worker {} has started firing events with an interval of {} ns and rate of "
                                + rate + " msg/sec",
                        Thread.currentThread().getId(), intervalInNanos);
            }
        } else if (this.rate == 0) {
            logger.debug("JMS Sender worker {} has started firing events with an unbounded rate",
                    Thread.currentThread().getId());
        }
        return intervalInNanos;
    }

    @Override
    public boolean isRunning() {
        return workerStateInfo.isRunning();
    }

    @Override
    public void stop() {
        workerStateInfo.setState(false, WorkerStateInfo.WorkerExitStatus.WORKER_EXIT_STOPPED, null);
    }

    @Override
    public void halt() {
        stop();
    }

    @Override
    public void run() {
        start();
    }
}
