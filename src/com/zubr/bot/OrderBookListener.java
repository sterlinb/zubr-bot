package com.zubr.bot;

/**
 * Interface to receive market updates from a {@link MarketObserver}.
 */
public interface OrderBookListener {
	/**
	 * Recieve the top of the new order books. Array sizes will equal the depth set when registering the listener. If the array is larger
	 * than the current order book, excess terminal entries will be null.
	 * @param bids bid entries, in increasing order of price
	 * @param asks ask entries, in decreasing order of price
	 */
	public void bookUpdate(OrderBookEntry[] bids, OrderBookEntry[] asks);
}
