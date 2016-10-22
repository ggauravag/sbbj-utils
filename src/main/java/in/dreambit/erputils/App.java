package in.dreambit.erputils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import javax.script.ScriptException;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.ConsoleAppender.Target;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import in.dreambit.erputils.bank.SBBJConnect;

public class App {

	private static String[] arguments;

	private static final int SUCCESS = 0;

	private static final int FAILURE = 1;

	private static final Logger rootLogger = LogManager.getRootLogger();

	public static String retrieveArgument(int index) {
		if (arguments.length > index) {
			if (arguments[index] != null && !arguments[index].isEmpty()) {
				return arguments[index];
			}
		}
		System.exit(FAILURE);
		return null;
	}

	private static void updateLogger(String filePath, Level level, boolean toConsole) {
		LoggerContext context = (LoggerContext) LogManager.getContext(false);

		Configuration configuration = context.getConfiguration();
		LoggerConfig rootLoggerConfig = configuration.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
		PatternLayout layout = PatternLayout.createLayout(PatternLayout.SIMPLE_CONVERSION_PATTERN, null, configuration,
				null, StandardCharsets.UTF_8, true, true, null, null);

		Appender appender = null;
		if (toConsole) {
			appender = ConsoleAppender.createAppender(layout, null, Target.SYSTEM_OUT, "Console", false, false, true);
		} else {
			appender = FileAppender.createAppender(filePath, null, null, "File", null, null, null, null, layout, null,
					"false", null, configuration);
		}

		appender.start();
		configuration.addAppender(appender);
		rootLoggerConfig.addAppender(appender, level, null);
		context.updateLoggers(configuration);
	}

	public static void logError(Throwable e) {
		System.err.println("Error: " + e.getMessage());
		rootLogger.error("ERROR while fetching balance: ", e);
	}

	public static void main(String[] args) throws ScriptException, IOException, URISyntaxException {

		if (args == null || args.length == 0) {
			System.err.println("No Argument Specified");
			rootLogger.error("No Argument specified");
			System.exit(FAILURE);
		}

		arguments = args;

		SBBJConnect sbbjConnect = new SBBJConnect();
		String accountType = null;
		String username = null;
		String password = null;
		String logFilePath = null;
		String outputType = "text";
		String keyFilePath = null;
		String txFilePath = null;
		int exitCode = SUCCESS;
		Level level = Level.DEBUG;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
			case "-username":
				username = retrieveArgument(i + 1);
				break;
			case "-password":
				password = retrieveArgument(i + 1);
				break;
			case "-type":
				accountType = retrieveArgument(i + 1);
				break;
			case "-output":
				outputType = retrieveArgument(i + 1);
				break;
			case "-log":
				logFilePath = retrieveArgument(i + 1);
				break;
			case "-level":
				level = Level.valueOf(retrieveArgument(i + 1));
				break;
			case "-keyFile":
				keyFilePath = retrieveArgument(i + 1);
				break;
			case "-txFile":
				txFilePath = retrieveArgument(i + 1);
				break;
			default:
				break;
			}
		}

		updateLogger(logFilePath, level, logFilePath == null);
		rootLogger.info("Parameters Received: ");
		rootLogger.info("Username: [{}]", username);
		rootLogger.info("Password: [{}]", password);
		rootLogger.info("Account Type: [{}]", accountType);
		rootLogger.info("Key File Path: [{}]", keyFilePath);
		rootLogger.info("Tx File Path: {}", txFilePath);
		rootLogger.info("Output Type: {}", outputType);
		rootLogger.info("Log File Path: {}", logFilePath);

		if ("CURRENT".equals(accountType)) {
			try {
				sbbjConnect.connectCorporate(username, password.toCharArray());
				if (keyFilePath == null)
					sbbjConnect.writeKeyDetails(null, outputType, true);
				else
					sbbjConnect.writeKeyDetails(keyFilePath, outputType, false);

				if (txFilePath == null)
					sbbjConnect.writeTransactionDetails(null, outputType, true);
				else
					sbbjConnect.writeTransactionDetails(txFilePath, outputType, false);
			} catch (Throwable e) {
				logError(e);
				exitCode = FAILURE;
			} finally {
				try {
					sbbjConnect.logout(sbbjConnect.CORP_LOGOUT_URL);
				} catch (IOException e) {
					logError(e);
					exitCode = FAILURE;
				}
			}
		} else if ("SAVINGS".equals(accountType)) {
			try {
				sbbjConnect.connectPersonal(username, password.toCharArray());

				if (keyFilePath == null)
					sbbjConnect.writeKeyDetails(null, outputType, true);
				else
					sbbjConnect.writeKeyDetails(keyFilePath, outputType, false);

				if (txFilePath == null)
					sbbjConnect.writeTransactionDetails(null, outputType, true);
				else
					sbbjConnect.writeTransactionDetails(txFilePath, outputType, false);

			} catch (Throwable e) {
				logError(e);
				exitCode = FAILURE;
			} finally {
				try {
					sbbjConnect.logout(sbbjConnect.CORP_LOGOUT_URL);
				} catch (IOException e) {
					logError(e);
					exitCode = FAILURE;
				}
			}
		}

		System.exit(exitCode);
	}
}
