## About the ZUBR bot
A model trading bot for the [ZUBR cryptocurrency derivative trading platform](https://zubr.io/). Includes connector classes that provide an abstracted interface to APIs [for receiving market data](https://spec.zubr.io/#websocket-subscriptions) and [for placing trade orders](https://spec.zubr.io/#byson-trading-protocol), as well as a working bot that uses them to execute a basic trading strategy.
### Running the bot
The bot is a non-user-interactive command line application. It requires a json configuration file to specify its numerous connection and strategy parameters, illustrated [here](https://github.com/sterlinb/zubr-bot/wiki/Example-bot-configuration-file). If you have downloaded the release jar file, placed the dependency jar files in /lib, and placed any classpath properties files (see Logging below) in /res, the bot may be run with the command:
```
java -cp zubr-bot-1.0.0.j:lib/*:res com.zubr.bot.ExampleZUBRRobot robotconfig.json
```
(If logging is not directed to a file, this may produce a large amount of output to the console.)
#### Getting the bot something to talk to
To connect the bot to a live testing server to place trades requires an account with ZUBR and obtaining an IP-specific login ID. This repository includes a simple dummy server creatively named DummyBYSONServer which can accept a connection and acknowledge basic trading messages, sufficient for the bot to run, but does not test message validity or simulate the complexities of the active trading environment.
### Bot Behavior
The robot attempts to maintain two limit orders:
* a buy order at `(current best purchase price + current best sale price)/2 - interest - shift * position`
* a sell order at `(current best purchase price + current best sale price)/2 + interest - shift * position`

Both orders are placed with volume equal to the "quoteSize" parameter, or the largest volume that will keep the bot within the bounds of `maxposition >= position >= -maxposition` if that is less. Orders are updated if either order is entirely filled, or when the best current prices change. The bot additionally has mechanisms to avoid violating message flooding limits.
### Connector classes
`BYSONChannel` and `MarketObserver` respectively implement connections to the trading gate and the order book subscription and each can make calls to an associated interface when messages arrive. `BYSONChannel` additionally provides support for outgoing trading messages.
### Logging
[SLF4J](https://www.slf4j.org/) is used for logging throughout. Initial development and testing was done with the SimpleLogger binding an a classpath properties file [such as this](https://github.com/sterlinb/zubr-bot/wiki/Example-simplelogger.properties-file), you can use whichever binding you please.
### Dependencies
In addition to SLF4J 1.7.30, the ExampleZUBRRobot and MarketObserver classes depend on [minimal-json](https://github.com/ralfstx/minimal-json) 0.9.5 and [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) 1.5.1. Java 8 is required.
### License
The code is provided under the terms of the [MIT License](http://opensource.org/licenses/MIT).
