package com.zubr.bot;
/**
 * An immutable record class for passing orderbook entries.
 */
public class OrderBookEntry {
	private final int instrument;
	private final long price;
	private final int amount;
	private final boolean buy;
	
	/**
	 * Creates an {@code OrderBookEntry}
	 * 
	 * @param instrument the id number of the instrument the order relates to.
	 * @param price the price of the order, multiplied by 10^9.
	 * @param amount the quantity of the order.
	 * @param buy true for buy, false for sell.
	 */
	public OrderBookEntry(int instrument, long price, int amount, boolean buy) {
		this.instrument = instrument;
		this.price = price;
		this.amount = amount;
		this.buy = buy;
	}
	
	public int getInstrument() { return instrument; }
	
	public long getPrice() { return price; }
	
	public int getAmount() { return amount; }
	
	public boolean isBuy() { return buy; }
}
