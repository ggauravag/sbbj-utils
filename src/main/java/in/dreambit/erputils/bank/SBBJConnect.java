/**
 * 
 */
package in.dreambit.erputils.bank;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Gaurav
 *
 */
public class SBBJConnect {

	/**
	 * Client side timeout for HTTP Connection
	 */
	private static final int TIMEOUT = 10000; // 10 seconds

	private static final Logger logger = LogManager.getRootLogger();

	/**
	 * Logout URL for Personal Banking
	 */
	private final String RETAIL_ACC_STMT_URL = "https://retail.sbbjonline.com/retail/quicklook.htm";

	/**
	 * Home Page URL for Personal Banking
	 */
	private final String RETAIL_HOME_PAGE_URL = "https://retail.sbbjonline.com/retail/mypage.htm";

	/**
	 * Password Change Pop up display URL
	 */
	private final String RETAIL_PASSWORD_CHANGE_URL = "https://retail.sbbjonline.com/retail/loginpwdchangedisplay.htm";

	/**
	 * Login Parameters POST Url for Personal Banking
	 */
	private final String RETAIL_LOGIN_SUBMIT_URL = "https://retail.sbbjonline.com/retail/loginsubmit.htm";

	/**
	 * Login Page URL for Personal Banking
	 */
	private final String RETAIL_PRE_LOGIN_URL = "https://retail.sbbjonline.com/retail/sbbjlogin.htm";

	private String accountNumber;

	private String branchCode;

	private String homePageUrl;

	private Map<String, String> sessionCookies;

	private Map<String, String> keyDetails;

	private ArrayList<List<String>> last10Transactions;

	/**
	 * URL for Account statement in Corporate Saral Banking
	 */
	private final String CORP_ACCOUNT_STMT_URL = "https://corp.sbbjonline.com/saral/quicklook.htm";

	/**
	 * Logout URL for Corporate Saral Banking
	 */
	public final String CORP_LOGOUT_URL = "https://corp.sbbjonline.com/saral/logout.htm";

	/**
	 * Login URL for Corporate Saral Banking
	 */
	private final String CORP_LOGIN_REFERRER_URL = "https://corp.sbbjonline.com/saral/sbbjlogin.htm";

	/**
	 * Login POST URL for Corporate Saral Banking
	 */
	private final String CORP_LOGIN_SUBMIT_URL = "https://corp.sbbjonline.com/saral/loginsubmit.htm";

	/**
	 * Pre Login URL for Corporate Saral Banking
	 */
	private final String CORP_PRE_LOGIN_URL = "https://corp.sbbjonline.com/saral/login.htm";

	/**
	 * Password Change Pop up display URL for Corporate Saral Banking
	 */
	private final String CORP_PASSWORD_CHANGE_URL = "https://corp.sbbjonline.com/saral/loginpwdchangedisplay.htm";

	/**
	 * Home Page URL for Corporate Saral Banking
	 */
	private final String CORP_HOME_PAGE_URL = "https://corp.sbbjonline.com/saral/mypage.htm";

	/**
	 * User Agent for all HTTP requests
	 */
	private final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.116 Safari/537.36";

	public SBBJConnect() {
		keyDetails = new HashMap<>();
		sessionCookies = new HashMap<>();
	}

	public void connectCorporate(String username, char[] password) throws Exception {
		sessionCookies.clear();
		logger.info("Starting SBBJ CORPORATE connection for username : {}", username);

		Response firstResponse = Jsoup.connect(CORP_PRE_LOGIN_URL).timeout(TIMEOUT).userAgent(USER_AGENT).execute();

		logger.debug("Connected to: {}", CORP_PRE_LOGIN_URL);
		logger.debug("Response Status: {}", firstResponse.statusCode());
		logger.trace("Response Body: {}", firstResponse.body());

		if (firstResponse.statusCode() != 200) {
			logger.error("Unexpected behaviour");
			throw new Exception("Unable to load login page");
		}

		sessionCookies.putAll(firstResponse.cookies());
		printCookies();

		Document loginPage = firstResponse.parse();

		if (!sessionCookies.containsKey("JSESSIONID")) {
			logger.warn("Did not get JSESSIONID cookie from regular flow, falling back to alternate solution");
			loginPage = getFirstResponse();
		}

		String shaKey = getKeyFromLoginPage(loginPage);

		Map<String, String> params = getFixedParam();
		params.put("userName", username);
		params.put("password", getEncryptedPassword(shaKey, username, password));
		params.put("shapassword", getEncryptedHash(shaKey, username, password));

		Response redirectResponse = Jsoup.connect(CORP_LOGIN_SUBMIT_URL).timeout(TIMEOUT).data(params)
				.followRedirects(false).referrer(CORP_PRE_LOGIN_URL).cookies(sessionCookies).userAgent(USER_AGENT)
				.execute();
		sessionCookies.putAll(redirectResponse.cookies());

		logger.debug("Connected to: {}", CORP_LOGIN_SUBMIT_URL);
		logger.debug("Response Status: {}", redirectResponse.statusCode());
		logger.trace("Response Body: {}", redirectResponse.body());

		if (redirectResponse.statusCode() != 302) {
			logger.error("Login submit did not redirect");
			if (redirectResponse.body().contains("You have already logged in")) {
				logger.warn("Already logged into the account");
				throw new RuntimeException("Account already logged in by other device");
			} else if (redirectResponse.body().contains("Invalid")) {
				logger.warn("Username / Password did not match");
				throw new RuntimeException("Username / Password did not match");
			} else {
				logger.warn("Unable to redirect to authorised page from login");
				throw new RuntimeException("Unable to redirect to authorised page from login");
			}
		}

		String locationUrl = redirectResponse.header("Location");

		logger.debug("Redirecting to: {}", locationUrl);

		Response authenticResponse = null;

		if (CORP_PASSWORD_CHANGE_URL.equals(locationUrl)) {
			logger.warn("Password validity is going to expire");
			authenticResponse = Jsoup.connect(CORP_HOME_PAGE_URL).timeout(TIMEOUT).data("userName", username)
					.data("password", "").data("keyString", "").cookies(sessionCookies).userAgent(USER_AGENT)
					.referrer(locationUrl).execute();

			homePageUrl = CORP_HOME_PAGE_URL;
		} else {

			homePageUrl = locationUrl;
			authenticResponse = Jsoup.connect(locationUrl).timeout(TIMEOUT).cookies(sessionCookies)
					.userAgent(USER_AGENT).referrer(CORP_LOGIN_REFERRER_URL).execute();
		}

		Document homePage = authenticResponse.parse();

		logger.debug("Connected to: {}", CORP_HOME_PAGE_URL);
		logger.debug("Response Status: {}", authenticResponse.statusCode());
		logger.trace("Response Body: {}", authenticResponse.body());

		if (authenticResponse.statusCode() != 200) {
			logger.error("Unable to by-pass change password popup");
			throw new RuntimeException("Errow while by-passing change password popup");
		}

		getAccountNumberAndBranchCode(homePage);

		readAccountBalance(CORP_ACCOUNT_STMT_URL);
	}

	public void connectPersonal(String username, char[] password) throws Exception {
		sessionCookies.clear();

		logger.info("Starting SBBJ Personal connection for username : {}", username);

		Response firstResponse = Jsoup.connect(RETAIL_PRE_LOGIN_URL).timeout(TIMEOUT).userAgent(USER_AGENT).execute();

		logger.debug("Connected to: {}", RETAIL_PRE_LOGIN_URL);
		logger.debug("Response Status: {}", firstResponse.statusCode());
		logger.trace("Response Body: {}", firstResponse.body());

		if (firstResponse.statusCode() != 200) {
			logger.error("Unexpected behaviour");
			throw new RuntimeException("Unable to load login page");
		}

		sessionCookies.putAll(firstResponse.cookies());
		printCookies();

		Document loginPage = firstResponse.parse();

		if (!sessionCookies.containsKey("JSESSIONID")) {
			logger.warn("Did not get JSESSIONID cookie from regular flow, falling back to alternate solution");
			loginPage = getFirstResponse();
		}

		String shaKey = getKeyFromLoginPage(loginPage);

		Map<String, String> params = getFixedParam();
		params.put("bankCode", "${headerValues['BankCode'][0]}");
		params.put("language", "english");
		params.put("userName", username);
		params.put("password", getEncryptedPassword(shaKey, username, password));
		params.put("shapassword", getEncryptedHash(shaKey, username, password));

		Response redirectResponse = Jsoup.connect(RETAIL_LOGIN_SUBMIT_URL).timeout(TIMEOUT).data(params)
				.followRedirects(false).referrer(RETAIL_PRE_LOGIN_URL).cookies(sessionCookies).userAgent(USER_AGENT)
				.execute();
		sessionCookies.putAll(redirectResponse.cookies());

		logger.debug("Connected to: {}", RETAIL_LOGIN_SUBMIT_URL);
		logger.debug("Response Status: {}", redirectResponse.statusCode());
		logger.trace("Response Body: {}", redirectResponse.body());

		if (redirectResponse.statusCode() != 302) {
			logger.error("Login submit did not redirect");
			if (redirectResponse.body().contains(" You have already logged in.")) {
				logger.warn("Already logged into the account");
				throw new Exception("Account already logged in by other device");
			} else if (redirectResponse.body().contains("Invalid")) {
				logger.warn("Username / Password did not match");
				throw new Exception("Username / Password did not match");
			} else {
				logger.warn("Unable to redirect to authorised page from login");
				throw new Exception("Unable to redirect to authorised page from login");
			}
		}

		String locationUrl = redirectResponse.header("Location");

		logger.debug("Redirecting to: {}", locationUrl);

		Response authenticResponse = null;

		if (RETAIL_PASSWORD_CHANGE_URL.equals(locationUrl)) {
			logger.warn("Password validity is going to expire");
			authenticResponse = Jsoup.connect(RETAIL_HOME_PAGE_URL).timeout(TIMEOUT).data("userName", username)
					.data("password", "").data("keyString", "").cookies(sessionCookies).userAgent(USER_AGENT)
					.referrer(locationUrl).execute();

			homePageUrl = RETAIL_HOME_PAGE_URL;
		} else {

			homePageUrl = locationUrl;
			authenticResponse = Jsoup.connect(locationUrl).timeout(TIMEOUT).cookies(sessionCookies)
					.userAgent(USER_AGENT).referrer(RETAIL_PRE_LOGIN_URL).execute();
		}

		Document homePage = authenticResponse.parse();

		logger.debug("Connected to: {}", RETAIL_HOME_PAGE_URL);
		logger.debug("Response Status: {}", authenticResponse.statusCode());
		logger.trace("Response Body: {}", authenticResponse.body());

		if (authenticResponse.statusCode() != 200) {
			logger.error("Unable to by-pass change password popup");
			throw new RuntimeException("Errow while by-passing change password popup");
		}

		getAccountNumberAndBranchCode(homePage);

		readAccountBalance(RETAIL_ACC_STMT_URL);
	}

	public void getAccountNumberAndBranchCode(Document homePage) {

		Elements links = homePage.getElementsByAttributeValueContaining("href", "javascript:submitQuickLookForm");
		String data = "";
		for (int i = 0; i < links.size(); i++) {
			Element link = links.get(i);
			data = link.attr("href");
		}

		Pattern pattern = Pattern.compile("javascript:[a-zA-Z]*\\('(?<accountNumber>\\d*)\\D*(?<branchCode>\\d*).*");

		Matcher matcher = pattern.matcher(data);

		boolean matches = matcher.matches();

		if (!matches) {
			logger.error("Unable to get account number / branch code from string: {}", data);
			throw new RuntimeException("No macth found for account number / branch code");
		}

		String accountNumber = matcher.group("accountNumber");
		String branchCode = matcher.group("branchCode");

		if (accountNumber != null && branchCode != null) {
			this.accountNumber = accountNumber;
			this.branchCode = branchCode;
			logger.debug("Account Number: {}, Branch Code: {}", accountNumber, branchCode);
		} else {
			logger.error("Unable to get account number and branch code from home page with string: {}", data);
			throw new RuntimeException("Errow while getting account number and branch code");
		}
	}

	public String getEncryptedHash(String shaKey, String username, char[] password) {
		logger.debug("Starting Hash encryption");
		ScriptEngineManager manager = new ScriptEngineManager();
		ScriptEngine engine2 = manager.getEngineByName("JavaScript");

		InputStream jsFileStream = SBBJConnect.class.getResourceAsStream("/sbbj/js/sha512.js");
		if (jsFileStream == null) {
			logger.error("File not found : /sbbj/js/md5.js/sbbj/js/sha512.js");
		}

		try {
			engine2.eval(new BufferedReader(new InputStreamReader(jsFileStream, StandardCharsets.UTF_8)));
		} catch (ScriptException e) {
			logger.error("Script Execution Error", e);
		}

		Invocable invocable2 = (Invocable) engine2;

		Object encryptedSHAPass = null;
		try {
			encryptedSHAPass = invocable2.invokeFunction("encryptSha2LoginPassword", shaKey, username,
					new String(password));
		} catch (NoSuchMethodException | ScriptException e) {
			logger.error("Method Execution Error", e);
		}
		logger.debug("Encrypted SHA Password: {}", encryptedSHAPass);
		return encryptedSHAPass.toString();
	}

	public String getEncryptedPassword(String shaKey, String username, char[] password) {
		logger.debug("Starting password encryption");
		ScriptEngineManager manager = new ScriptEngineManager();

		ScriptEngine engine = manager.getEngineByName("JavaScript");
		logger.debug("Got script engine");
		InputStream jsFileStream = SBBJConnect.class.getResourceAsStream("/sbbj/js/md5.js");
		if (jsFileStream == null) {
			logger.error("File not found : /sbbj/js/md5.js");
		}

		logger.debug("Loaded JS file");

		try {
			engine.eval(new BufferedReader(new InputStreamReader(jsFileStream, StandardCharsets.UTF_8)));
		} catch (ScriptException e) {
			logger.error("Script Execution Error", e);
		}

		logger.debug("Invoking function");
		Invocable invocable = (Invocable) engine;

		Object encryptedPassword = null;
		try {
			encryptedPassword = invocable.invokeFunction("encryptLoginPassword", shaKey, username,
					new String(password));
		} catch (NoSuchMethodException e) {
			logger.error("Method Execution Error", e);
		} catch (ScriptException e) {
			logger.error("Script Execution Error", e);
		}
		logger.debug("Encrypted Password: {}", encryptedPassword);

		return encryptedPassword.toString();
	}

	public Document getFirstResponse() throws IOException {
		Process cmdProc = Runtime.getRuntime().exec("curl -v https://corp.sbbjonline.com/saral/login.htm");
		BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(cmdProc.getInputStream()));
		String line;
		StringBuilder html = new StringBuilder();
		while ((line = stdoutReader.readLine()) != null) {
			html.append(line);
		}

		logger.trace("Response : {}", html.toString());

		Pattern jsessionIdPattern = Pattern.compile(": JSESSIONID=(.*?);");

		StringBuilder error = new StringBuilder();
		BufferedReader stderrReader = new BufferedReader(new InputStreamReader(cmdProc.getErrorStream()));
		while ((line = stderrReader.readLine()) != null) {
			error.append(line);
			if (line.contains("Set-Cookie")) {
				Matcher matcher = jsessionIdPattern.matcher(line);
				if (matcher.find()) {
					String cookie = matcher.group(1);
					logger.info("Cookie Found JSESSIONID={}", cookie);
					sessionCookies.put("JSESSIONID", cookie);
				} else {
					logger.error("curl fallback mechanism failed, unable to get cookie JSESSIONID");
				}
			}
		}

		logger.trace("Error Response : {}", error.toString());

		Document loginPage = Jsoup.parse(html.toString(), "https://corp.sbbjonline.com/saral");
		if (cmdProc.exitValue() == 0) {
			logger.debug("Curl Command executed successfully. Process to retriev cookie and sha key start...");
		} else {
			logger.error("Error in firing curl command");
		}
		return loginPage;
	}

	public Map<String, String> getFixedParam() throws UnsupportedEncodingException {
		Map<String, String> params = new HashMap<>();

		params.put("hdnKioskID", "");
		params.put("hdnKModeUserName", "");
		params.put("errorCode", "");
		params.put("bankCode", "1");
		params.put("firstTimeLogin", "No");
		params.put("browserName", URLEncoder.encode(USER_AGENT, StandardCharsets.UTF_8.displayName()));

		return params;
	}

	/**
	 * @return the keyDetails
	 */
	public Map<String, String> getKeyDetails() {
		return keyDetails;
	}

	/**
	 * @param loginPage
	 * @return
	 */
	private String getKeyFromLoginPage(Document loginPage) {
		Element submitButton = loginPage.getElementById("Button2");

		if (submitButton == null) {
			logger.error("No submit button found on login page");
			throw new RuntimeException("No submit button found on login page");
		}

		String functionCall = submitButton.attr("onclick");

		String shaKey = getSHAKey(functionCall);
		return shaKey;
	}

	/**
	 * @return the last10Transactions
	 */
	public ArrayList<List<String>> getLast10Transactions() {
		return last10Transactions;
	}

	public String getSHAKey(String functionCall) {
		String shaKey = null;
		functionCall = functionCall.replace("return submitLoginSha('", "");
		functionCall = functionCall.replace("');", "");
		shaKey = functionCall;
		logger.debug("SHA Key: {}", shaKey);
		return shaKey;
	}

	public void logout(String logoutUrl) throws IOException {

		if (homePageUrl == null) {
			logger.error("User did not logged in successfully, cannot logout !");
			return;
		}

		Response logOutResponse = Jsoup.connect(logoutUrl).cookies(sessionCookies).referrer(homePageUrl)
				.userAgent(USER_AGENT).execute();
		if (logOutResponse.statusCode() == 200) {
			logger.debug("Logout Successfully");
		} else {
			logger.error("Unable to logout, response code: {}", logOutResponse.statusCode());
		}
	}

	public void printCookies() {
		for (Map.Entry<String, String> cookie : sessionCookies.entrySet()) {
			logger.debug("{} - {}", cookie.getKey(), cookie.getValue());
		}
	}

	public void readAccountBalance(String accountBalanceUrl) throws IOException {
		Response response = Jsoup.connect(accountBalanceUrl).data("accountNo", accountNumber)
				.data("branchCode", branchCode).referrer(homePageUrl).userAgent(USER_AGENT).cookies(this.sessionCookies)
				.execute();

		Document document = response.parse();

		Elements elementsValue = document.getElementsByAttributeValue("class", "formDatanobrdr");
		Elements elementsKey = document.getElementsByAttributeValue("class", "formLabelBold");
		keyDetails.clear();
		logger.debug("Total Number of Informative Columns: {}", elementsValue.size());
		for (int i = 0; i < elementsValue.size(); i++) {
			Element value = elementsValue.get(i);
			Element key = elementsKey.get(i);
			keyDetails.put(key.text(), value.text());
		}

		Element element = document.getElementById("tblAcct");

		Element tbody = element.getElementsByTag("tbody").get(0);

		Elements rows = tbody.getElementsByTag("tr");
		last10Transactions = new ArrayList<List<String>>();
		for (int i = 0; i < rows.size(); i++) {
			List<String> tableRow = new ArrayList<>();
			Element row = rows.get(i);
			Elements columns = row.getElementsByTag("td");

			for (int j = 0; j < columns.size(); j++) {
				Element column = columns.get(j);

				if (j == 0 || j == 1) {
					tableRow.add(column.text());
				} else {
					String text = column.html().replaceAll("&nbsp;", " ");
					String trimmedText = StringUtils.trim(text);
					tableRow.add(trimmedText);
				}
			}

			last10Transactions.add(tableRow);
		}
	}

	public void writeKeyDetails(String filePath, String type, boolean toConsole)
			throws JsonGenerationException, JsonMappingException, IOException {

		if ("json".equals(type)) {
			ObjectMapper mapper = new ObjectMapper();

			if (toConsole) {
				String serializedString = mapper.writeValueAsString(this.keyDetails);
				System.out.println(serializedString);
			} else {
				mapper.writeValue(new File(filePath), this.keyDetails);
			}

		} else if ("text".equals(type) && !toConsole) {

			List<String> lines = new ArrayList<>();
			for (Entry<String, String> detail : this.keyDetails.entrySet()) {
				lines.add(String.format("%20s: %20s", detail.getKey(), detail.getValue()));
			}

			if (toConsole) {
				for (String line : lines)
					System.out.println(line);
			} else {
				Files.write(Paths.get(filePath), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
						StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
			}
		}

	}

	public void writeTransactionDetails(String filePath, String type, boolean toConsole)
			throws JsonGenerationException, JsonMappingException, IOException {

		if ("json".equals(type)) {
			ObjectMapper mapper = new ObjectMapper();
			if (toConsole) {
				String serializedString = mapper.writeValueAsString(this.last10Transactions);
				System.out.println(serializedString);
			} else {
				mapper.writeValue(new File(filePath), this.last10Transactions);
			}
		} else if ("text".equals(type)) {

			List<String> lines = new ArrayList<>();
			for (List<String> transaction : this.last10Transactions) {
				StringBuilder finalLine = new StringBuilder();

				if (transaction.size() < 5) {
					continue;
				}

				for (String value : transaction) {
					finalLine.append(String.format("%40.40s\t", value));
				}
				lines.add(finalLine.toString());
			}

			if (toConsole) {
				for (String line : lines)
					System.out.println(line);
			} else {
				Files.write(Paths.get(filePath), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
						StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
			}

		}
	}

}
