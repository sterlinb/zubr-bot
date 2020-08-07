package com.zubr.bot;

/**
 * A counter for keeping track of the number of events that have occurred within a specified time interval, in order to avoid exceeding a rate limit.
 * <p>
 * {@code FloodTracker} is not thread-safe. If concurrently accessed from multiple threads, external synchronization is required.
 */
public class FloodTracker {
	private long[] timestamps;
	private int oldest;
	private int count;
	private long period;
	
	/**
	 * Creates a counter with the specified event limit and time interval.
	 * @param capacity the maximum allowed number of events.
	 * @param period the time period (in nanoseconds) within which {@code capacity} must not bbe exceeded
	 */
	public FloodTracker(int capacity, long period) {
		timestamps = new long[capacity];
		this.period = period;
		oldest = count = 0;
	}
	
	/**
	 * Checks the number of additional events currently allowed.
	 * <p>
	 * Identical to {@code available(System.nanoTime())}.
	 * @return the number of events that could be added without exceeding the limit.
	 */
	public int available() {
		return available(System.nanoTime());
	}
	
	/**
	 * Checks the number of additional events currently allowed.
	 * <p>
	 * Useful to avoid redundant calls to {@code System.nanoTime()} if the caller also needs the timestamp. Not safe for determining
	 * number of events that will be allowed at a future timepoint: events prior to the interval ending at {@code time} will be forgotten.
	 * @param time the {@code System.nanoTime()}.
	 * @return the number of events that could be added without exceeding the limit.
	 */
	public int available(long time) {
		advance(time);
		return timestamps.length - count;
	}
	
	/**
	 * Adds an event to this counter, if allowed.
	 * <p>
	 * Identical to {@code add(System.nanoTime())}
	 * @return {@code true} if the event was added, {@code false} if adding the event would have exceeded the limit.
	 */
	public boolean add() {
		return add(System.nanoTime());
	}
	
	/**
	 * Adds an event to this counter, if allowed.
	 * <p>
	 * Useful to avoid redundant calls to {@code System.nanoTime()} if the caller also needs the timestamp. May be unsafe to use with
	 * future timepoints: events prior to the interval ending at {@code time} will be forgotten, and if events are not added in
	 * chronological order some events may be retained in the count after they should have expired.
	 * @param time the {@code System.nanoTime()}.
	 * @return {@code true} if the event was added, {@code false} if adding the event would have exceeded the limit.
	 */
	public boolean add(long time) {
		advance(time);
		if(count < timestamps.length) {
			count++;
			timestamps[(oldest + count) % timestamps.length] = time;
			return true;
		} else
			return false;
	}
	
	/**
	 * Advances internal index to forget old events.
	 * @param time the current time. Events previously added with timestamps {@code < time - period} are forgotten
	 */
	private void advance(long time) {
		time = time - period;
		while(count > 0 && timestamps[oldest] < time) {
			oldest = (oldest + 1)%timestamps.length;
			count--;
		}
	}
}
