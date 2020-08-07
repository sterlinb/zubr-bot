package com.zubr.bot;

/**
 * Interface to receive raw BYSON messages from a {@link InputChannelWorker}.
 */
public interface BYSONRawListener {
	/**
	 * Called when a complete BYSON message has been read.
	 * @param message the BYSON message including header.
	 */
	public void messageRecieved(byte[] message);
}
