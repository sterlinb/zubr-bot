package com.zubr.bot;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@code Runnable} that maintains an outgoing BYSON connection. When running, an instance writes raw messages to the connection
 * and automatically sends heartbeat messages when required.
 * @author Bram
 *
 */
public class OutChannelWorker implements Runnable {
	
	final Logger logger = LoggerFactory.getLogger(OutChannelWorker.class);
	private LinkedBlockingDeque<byte[]> workqueue;
	private OutputStream out;
	private long lastsend, heartbeat;
	
	private volatile boolean stopped;
	
	/**
	 * Initializes an {@code OutChannelWorker}. Does not perform any activity until {@code run()} is called.
	 * @param out stream to write messages to.
	 * @param lastsend {@code System.nanoTime()} at which the last message was sent, to time the first heartbeat.
	 * @param heartbeat required heartbeat interval in nanoseconds.
	 */
	public OutChannelWorker(OutputStream out, long lastsend, long heartbeat) {
		this.out = out;
		this.lastsend = lastsend;
		this.heartbeat = heartbeat;
		workqueue = new LinkedBlockingDeque<>();
		stopped = false;
	}

	/**
	 * Writes enqueued messages and heartbeats to the connection. Runs indefinitely unless {@code stop()} is called.
	 */
	@Override
	public void run() {
		while(true) {
			byte[] message = null;
			try {
				long delay = (heartbeat*2/3 - (System.nanoTime() - lastsend)) / 1000000;
				logger.trace("max block should be {} ms", delay);
				message = workqueue.poll(delay, TimeUnit.MILLISECONDS);
			} catch(InterruptedException e) {}
			if(message == null)
				message = SEQUENCE_MESSAGE;
			
			if(stopped) {
				logger.info("Worker stopped");
				return;
			}

			logger.trace("Worker sending {}", message);
			//System.out.println(System.currentTimeMillis() + Arrays.toString(message));
			lastsend = System.nanoTime();
			try {
				out.write(message);
				out.flush();
			} catch (IOException e) {
				logger.warn("Output stream exception {}", e);
			}
		}
	}
	
	private static final byte[] SEQUENCE_MESSAGE = {0x08, 0x00, (byte) 0x8f, 0x13, 0x04, 0x1c, 2, 0, (byte) 0xff, (byte) 0xff,
			(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
	
	/**
	 * Adds a message to the sending queue.
	 * @param message message to send.
	 */
	public void enqueue(byte[] message) {
		byte[] mbuff = Arrays.copyOf(message, message.length);
		workqueue.addLast(mbuff);
	}
	
	/**
	 * Stops all further outgoing messages. Including heartbeat messages and any messages still in the queue. Causes {@code run} to return,
	 * but not immediately.
	 */
	public void stop() {
		stopped = true;
	}
}