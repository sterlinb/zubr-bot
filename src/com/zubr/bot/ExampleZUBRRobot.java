package com.zubr.bot;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;

/**
 * Working example trading bot. Trades using {@link BYSONChannel} and {@link MarketObserver} to handle communication with the market.
 * <p>
 * The trading strategy is straightforward. The robot attempts to maintain two limit orders:<ul>
 * <li>a buy order at (current best purchase price + current best sale price) / 2 - interest - shift * position</li>
 * <li>a sell order at (current best purchase price + current best sale price) / 2 + interest - shift * position</li></ul>
 * Both orders are placed with volume equal to a provided parameter, or the largest volume that will keep the bot within the bounds of
 * {@code maxposition >= position >= -maxposition} if that is less. Orders are updated if either order is entirely filled, or when the
 * best current prices change. The bot additionally has mechanisms to avoid violating message flooding limits.
 * <p>
 * Important use note: when exiting the bot will attempt to log or print the highest used request ID. This must be used to determine the next
 * available request ID for configuring subsequent runs.
 */
public class ExampleZUBRRobot implements BYSONMessageHandler, OrderBookListener {
	private static final Logger logger = LoggerFactory.getLogger(ExampleZUBRRobot.class);

	/*
	 * Robot configuration parameters: desired volume for orders, parameters for calculating prices, the instrument to trade,
	 * and the maximum authorized position in that instrument.
	 */
	private final int standardvolume;
	private final long interest;
	private final long shift;
	private final int instrument;
	private final int maxposition;
	private final long increment;
	
	protected int position;
	protected BYSONChannel tradeinterface;
	protected MarketObserver orderbook;
	
	// All mutable fields are only accessed from the single-thread ExecutorService, ensuring threadsafe internal operations.
	protected ExecutorService decoupler; 

	private long bidprice = 0;
	private int bidamount = 0;
	private long askprice = 0;
	private int askamount = 0;
	
	private long marketbid;
	private long marketask;
	
	private long bidid = 0;
	private long askid = 0;
	
	private long bidreqid = 0;
	private long askreqid = 0;
	private long lastreqid = 0;
	
	private long desiredbidprice = 0;
	private int desiredbidamount = 0;
	private long desiredaskprice = 0;
	private int desiredaskamount = 0;

	private boolean revisionpending = false;
	
	private volatile long unlocktime = 0;
	private FloodTracker flood;
	
	private Thread shutdownhook;
	
	/**
	 * Creates and initializes the robot. The robot does not connect to any services or start trading until {@code start()} is called.
	 * @param tradevolume the size of orders placed, unless such an order would cause maxposition to be exceeded.
	 * @param interest interest * 10^9. Trading strategy parameter, see class description for explanation.
	 * @param shift interest * 10^9. Trading strategy parameter, see class description for explanation.
	 * @param instrument id numeber for instrument to trade.
	 * @param increment the minimum allowed price increment. Depends on instrument.
	 * @param maxposition maximum trading position allowed. Robot will keep position between maxposition and -maxposition.
	 * @param position initial position held in the instrument.
	 * @param websocketEndpointURI URI of the ZUBR WebSocket API access point.
	 * @param BYSONaccessPoint address for the ZUBR BYSON protocol trading gate.
	 * @param BYSONlogin BYSON gate login id
	 * @param BYSONaccount BYSON gate account id
	 * @param firstreq first request ID to use. Must be greater than any previously used request ID or messages will be rejected.
	 * @param floodline the number of messages in one second that will trigger antiflooding penalties from the BYSON gate.
	 */
	public ExampleZUBRRobot(int tradevolume, long interest, long shift, int instrument, long increment, int maxposition, int position,
URI websocketEndpointURI, InetSocketAddress BYSONaccessPoint, int BYSONlogin, long BYSONaccount, long firstreq, int floodline) {
		this.standardvolume = tradevolume;
		this.interest = interest;
		this.shift = shift;
		this.instrument = instrument;
		this.maxposition = maxposition;
		this.position = position;
		this.increment = increment;
		this.flood = new FloodTracker(floodline-1, 1000000000L);
		
		tradeinterface = new BYSONChannel(BYSONaccessPoint, BYSONlogin, BYSONaccount, firstreq);
		tradeinterface.setMessageHandler(this);
		orderbook = new MarketObserver(websocketEndpointURI, instrument);
		orderbook.setListener(this, 2);
	}
	
	/**
	 * Starts the robot and connects to servers.
	 */
	public void start() {
		decoupler = Executors.newSingleThreadExecutor();
		Runtime.getRuntime().addShutdownHook(shutdownhook = new Thread() {
			@Override
			public void run() {
				unlocktime = Long.MAX_VALUE; // Prevent any further market orders
				logger.info("Robot closing detected, current last used requestID is {}", lastreqid);
				if(!logger.isInfoEnabled())
					System.out.println("Robot closing detected, current last used requestID is " + lastreqid);
				try {
					decoupler.execute(() -> {
						decoupler.shutdown();
						lastreqid = tradeinterface.sendOrderMassCancelRequest(instrument, (byte) -1);
						logger.info("Last used requestID is {}", lastreqid);
						if(!logger.isInfoEnabled())
							System.out.println("Last used requestID is " + lastreqid);
					});
					return;
				} catch (RejectedExecutionException e) {
					logger.info("Last used requestID is {}", lastreqid); // If decoupler is in shutdown order canceling should be done.
					if(!logger.isInfoEnabled())
						System.out.println("Last used requestID is " + lastreqid);
					return;
				}
			}});
		try {
			tradeinterface.connect();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
		orderbook.connect();
	}

	/**
	 * Handles order execution reports. Updates internal position tracker, and updates orders if an order has entirely cleared.
	 */
	@Override
	public void handleExecutionReport(long orderid, long price, int size, int remain, long timestamp) {
		decoupler.execute(() -> {
			
			if(orderid == bidid) {
				bidamount = remain;
				position += size;
				logger.info("Bought {} at {}, {} left in order", size, price, remain);
			} else if(orderid == askid) {
				askamount = remain;
				position -= size;
				logger.info("Sold {} at {}, {} left in order", size, price, remain);
			} else
				logger.error("Unrecognized order execution. {} does not match current orders {} or {}. Position record is now in error", 
						orderid, bidid, askid);
			
			if(remain == 0)
				replaceOrders();
		});
	}

	/**
	 * Registers successful order placement.
	 */
	@Override
	public void handleNewOrderSingleReport(long orderid, long price, int size, boolean buy, long requestid,
			long timestamp) {
		decoupler.execute(() -> {
			installOrder(orderid, price, size, requestid);
		});
	}

	/**
	 * Handles successful order replacement.
	 */
	@Override
	public void handleOrderReplaceReport(long orderid, long price, int size, long requestid, long oldorderid,
			long timestamp) {
		decoupler.execute(() -> {
			installOrder(orderid, price, size, requestid);
		});
	}


	/**
	 * Handles order replacement rejection. If circumstances permit, places a new order in place of the failed replacement.
	 */
	@Override
	public void handleOrderReplaceReject(long requestid, byte reason) {
		decoupler.execute(() -> {
			long time = System.nanoTime();
			if(requestid == bidreqid) {
				if(desiredbidamount == 0) {
					logger.debug("Failed bid order replacement, no bid now desired");
					bidreqid = 0;
				} else if(time < unlocktime || !flood.add(time)) {
					logger.debug("Failed bid order replacement, cannot place new order due to flood penalty");
					bidreqid = 0;
				} else {
					logger.debug("Failed bid order replacement, placing new order");
					bidreqid = tradeinterface.sendNewOrderSingle(desiredbidprice, desiredbidamount, true, instrument);
					lastreqid = Math.max(bidreqid, lastreqid);
				} 
			}
			else if(requestid == askreqid) {
				if(desiredaskamount == 0) {
					logger.debug("Failed ask order replacement, no ask now desired");
					askreqid = 0;
				} else if(time < unlocktime || !flood.add(time)) {
					logger.debug("Failed ask order replacement, cannot place new order due to flood penalty");
					askreqid = 0;
				} else {
					logger.debug("Failed ask order replacement, placing new order");
					askreqid = tradeinterface.sendNewOrderSingle(desiredaskprice, desiredaskamount, false, instrument);
					lastreqid = Math.max(askreqid, lastreqid);
				}
			}
			else
				logger.warn("Unidentified order rejection received. {} does not match outstanding request ids {} or {}", requestid,
						bidreqid, askreqid);
			if(reason != 4)
				logger.warn("Order replacement failed with unexpected reason code: {}", reason);
		});
	}
	
	/**
	 * Evaluates orderbook update and revises orders if prices have changed. Uses the top 2 entries of each book to find the top price 
	 * excluding its own orders.
	 */
	@Override
	public void bookUpdate(OrderBookEntry[] bids, OrderBookEntry[] asks) {
		decoupler.execute(() -> {
			OrderBookEntry topbid = bids[0];
			if(topbid != null && topbid.getPrice() == bidprice && topbid.getAmount() <= bidamount)
				topbid = bids[1];
			
			OrderBookEntry topask = asks[0];
			if(topask != null && topask.getPrice() == askprice && topask.getAmount() <= askamount)
				topask = asks[1];
			
			if(topask != null && topbid != null && (topask.getPrice() != marketask || topbid.getPrice() != marketbid)) {
				logger.debug("Market asking price updated {} to {}, bidding price updated {} to {}", marketask, topask.getPrice(),
						marketbid, topbid.getPrice());
				marketask = topask.getPrice();
				marketbid = topbid.getPrice();
				replaceOrders();
			}
		});
	}

	/**
	 * Registers order rejection.
	 */
	@Override
	public void handleNewOrderReject(long requestid, byte reason) {
		decoupler.execute(() -> {
			logger.error("Order {} rejected with code {}", requestid, reason);
			clearRequest(requestid);
			if(reason == 2 || reason == 3 || reason == 13) {
				logger.error("Order rejection indicates irrecoverable error");
				shutdown();
				System.exit(1);
			}
		});
	}

	/**
	 * Registers message rejection.
	 */
	@Override
	public void handleMessageReject(long requestid, byte reason, int fieldid) {
		decoupler.execute(() -> {
			logger.error("Message rejected: request {} failed with code {} problem field {}", requestid, reason, fieldid);
			clearRequest(requestid);
		});
	}

	/**
	 * Registers rejection and activates flooding lockout.
	 */
	@Override
	public void handleFloodReject(long requestid, long timeout) {
		unlocktime = System.nanoTime() + timeout;
		logger.warn("Message flooding! Blocked for {} nanoseconds", timeout);
		decoupler.execute(() -> { clearRequest(requestid); });
	}

	/**
	 * Shuts down robot and program if session is terminated.
	 */
	@Override
	public void handleTerminate(byte reason) {
		logger.error("BYSON connection terminated with code {}", reason);
		shutdown();
		System.exit(1);
	}
	
	/**
	 * Stops robot trading and attempts to cancel any outstanding orders. Does not shut down network connections or end the process!
	 * <p>
	 * Only called in exceptional circumstances in current implementation.
	 */
	private void shutdown() {
		decoupler.shutdown();
		unlocktime = Long.MAX_VALUE; // Prevent any further market requests
		lastreqid = tradeinterface.sendOrderMassCancelRequest(instrument, (byte) -1);
		logger.info("Last used requestID is {}", lastreqid);
		if(!logger.isInfoEnabled())
			System.out.println("Last used requestID is " + lastreqid);
		Runtime.getRuntime().removeShutdownHook(shutdownhook);
	}

	private void clearRequest(long requestid) {
		lastreqid = Math.max(requestid, lastreqid);
		if(requestid == bidreqid)
			bidreqid = 0;
		else if(requestid == askreqid)
			askreqid = 0;
		else
			logger.warn("Request {} cleared but not recognized", requestid);
	}
	
	/**
	 * Calculates the new bid and ask prices and places orders if allowed.
	 * <p>
	 * Note that the calculated prices must be rounded off to an increment of <code>increment</code>. Failing to do so results in rejected
	 * orders.
	 */
	protected void replaceOrders() {
		long marketmid = Long.divideUnsigned(marketask + marketbid, 2L);
		long positionadjust = shift * position;
		desiredbidamount = Math.min(maxposition - position, standardvolume);
		desiredbidprice = marketmid - interest - positionadjust;
		desiredbidprice = desiredbidprice - desiredbidprice%increment + (desiredbidprice%increment < increment/2?0L:increment);
		desiredaskamount = Math.min(position + maxposition, standardvolume); // minposition = -maxposition
		desiredaskprice = marketmid + interest - positionadjust;
		desiredaskprice = desiredaskprice - desiredaskprice%increment + (desiredaskprice%increment < increment/2?0L:increment);
		
		if(askreqid == 0 && bidreqid == 0 && unlocktime < System.nanoTime())
			dispatchOrders();
		else
			revisionpending = true;
	}
	
	/**
	 * Updates the record of current orders and outstanding requests based on order request responses.
	 * 
	 * @param orderid new order ID
	 * @param price new order price
	 * @param size new order size
	 * @param requestid associated requestID, to recognize which order this is.
	 */
	private void installOrder(long orderid, long price, int size, long requestid) {
		if(requestid == bidreqid) {
			bidid = orderid;
			bidprice = price;
			bidamount = size;
			bidreqid = 0;
			logger.debug("Installed new buy order, id {}", bidid);
		} else if(requestid == askreqid) {
			askid = orderid;
			askprice = price;
			askamount = size;
			askreqid = 0;
			logger.debug("Installed new sell order id {}", askid);
		} else
			logger.warn("Unidentified order report received. {} does not match outstanding request ids {} or {}", requestid,
					bidreqid, askreqid);
		
		if(askreqid == 0 && bidreqid == 0 && revisionpending)
			dispatchOrders();
	}

	private void dispatchOrders() {
		long time = System.nanoTime();
		if(flood.available(time) > (desiredbidamount > 0?1:0) + (desiredaskamount > 0?1:0)) {
			if(desiredbidamount > 0) {
				flood.add(time);
				if(bidamount > 0) {
					logger.debug("sending buy order replacement request at price {}", desiredbidprice);
					bidreqid = tradeinterface.sendOrderReplaceRequest(bidid, desiredbidprice, desiredbidamount);
				} else {
					logger.debug("sending new buy order request at price {}", desiredbidprice);
					bidreqid = tradeinterface.sendNewOrderSingle(desiredbidprice, desiredbidamount, true, instrument);
				}
			}

			if(desiredaskamount > 0) {
				flood.add(time);
				if(askamount > 0) {
					logger.debug("sending sell order replacement request at price {}", desiredaskprice);
					askreqid = tradeinterface.sendOrderReplaceRequest(askid, desiredaskprice, desiredaskamount);
				} else {
					logger.debug("sending new sell order request at price {}", desiredaskprice);
					askreqid = tradeinterface.sendNewOrderSingle(desiredaskprice, desiredaskamount, false, instrument);
				}
			}

			lastreqid = Math.max(Math.max(bidreqid, askreqid), lastreqid);
			revisionpending = false;
		} else
			logger.debug("Order dispatch prevented by flood limiter");
	}

	/**
	 * Sets up the robot based on a json configuration file and launches it.
	 * @param args first command line argument must be a json configuration file with the required properties.
	 */
	public static void main(String[] args) {
		logger.debug("Loading configuration");
		JsonObject config = null;
		try(FileReader confile = new FileReader(args[0])) {
			config = Json.parse(confile).asObject();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}

		String bookendpoint = config.get("wsEndpoint").asString();
		int instrument = config.get("instrument").asInt();
		URI book = null;
		try {
			book = new URI(bookendpoint);
		} catch (URISyntaxException e) {
			logger.error("Invalid endpoint URI");
			System.exit(1);
		}
		InetSocketAddress byson = new InetSocketAddress(config.get("bysonEndpoint").asString(), config.get("bysonPort").asInt());
		int login = config.get("bysonLogin").asInt();
		long account = config.get("bysonAccount").asLong();
		
		int tradevolume = config.get("quoteSize").asInt();
		int position = config.get("initialPosition").asInt();
		int max = config.get("maxPosition").asInt();
		
		long interest = Math.round(config.get("interest").asDouble() * 1000000000);
		long shift = Math.round(config.get("shift").asDouble() * 1000000000);
		long increment = Math.round(config.get("priceIncrement").asDouble() * 1000000000);
		logger.trace("As mantissa, Interest = {}, shift = {}, price increment = {}", interest, shift, increment);
		
		long firstreq = config.getLong("requestIDStart", 1);
		logger.info("Starting with requestID {}", firstreq);
		
		int flood = config.getInt("floodLimit", 100);
		
		ExampleZUBRRobot robot = new ExampleZUBRRobot(tradevolume, interest, shift, instrument, increment, max, position,
				book, byson, login, account, firstreq, flood);
		robot.start();
	}
}
