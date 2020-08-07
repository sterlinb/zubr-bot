package com.zubr.bot;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A runnable that reads BYSON messages and delivers them to a {@link BYSONRawListener}.
 */
public class InputChannelWorker implements Runnable {
	private InputStream in;
	private List<BYSONRawListener> listeners;
	
	private static final Logger logger = LoggerFactory.getLogger(InputChannelWorker.class);
	
	private volatile boolean stopped;
	
	/**
	 * Initializes an {@code InputChannelWorker}. To actually do work, {@code run()} must be called.
	 * @param in connection {@code InputStream}
	 */
	public InputChannelWorker(InputStream in) {
		this.in = in;
		listeners = new ArrayList<>();
		stopped = false;
	}
	
	private byte[] readmessage() throws IOException {
		byte[] head = new byte[2], message;
		int n = in.read(head);
		while(n < 2) {
			//logger.trace("Waiting for message size");
			int step = in.read(head, n, 2-n);
			if(step == -1) {
				logger.error("Input stream ended waiting after byte {} {}", n, head);
				throw new IOException("Input stream ended mid-message");
			}
			n += step;
		}
		int size = (head[0] & 0xff) | ((head[1] & 0xff) << 8);
		message = new byte[size+8];
		System.arraycopy(head, 0, message, 0, head.length);
		
		while(n < message.length) {
			int step = in.read(message, n, message.length-n);
			if(step == -1) {
				logger.error("Input stream ended waiting after byte {} {}", n, message);
				throw new IOException("Input stream ended mid-message");
			}
			n += step;
		}
		return message;
	}
	
	/**
	 * Adds a {@code BYSONRawListener} that will be passed messages that are read.
	 * @param listener listener to be added.
	 * @throws NullPointerException if {@code listener == null}
	 */
	public void addListener(BYSONRawListener listener) {
		if(listener == null)
			throw new NullPointerException();
		
		synchronized(listeners) {
			listeners.add(listener);
		}
	}

	/**
	 * Reads and delivers messages. Runs indefinitely unless {@code stop()} is called.
	 * <p>
	 * Only evaluates the body size from the message header - no effort is made to validate message content.
	 */
	@Override
	public void run() {
		while(true) {
			byte[] message = null;

			logger.debug("Getting message");
			try {
				message = readmessage();
			} catch (IOException e) {
				if(stopped)
					return;
				logger.error("Message reading exception: {}", e);
				continue;
			}

			logger.debug("Passing message to listeners");
			synchronized(listeners) {
				if(listeners.size() > 1) {
					for(BYSONRawListener listener:listeners) {
						byte[] handoff = new byte[message.length];
						System.arraycopy(message, 0, handoff, 0, message.length);
						listener.messageRecieved(handoff);
					}
				} else if (listeners.size() == 1)
						listeners.get(0).messageRecieved(message);
			}
			
		}
	}

	/**
	 * Instructs the worker to stop. It will only do so when it can no longer read input!
	 */
	public void stop() {
		stopped = true;
	}
}
