/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
// Java utilities
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import javax.jms.JMSException;
import javax.mail.internet.AddressException;

// Log4j
import org.apache.log4j.Category;

// JDOM
import org.jdom.Document;
import org.jdom.Element;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.MailServiceConfig;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.loggingutils.MailService;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

//AWS Message Object API (MOA)
import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountNotification;
import com.amazon.aws.moa.jmsobjects.user.v1_0.AccountUser;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserProfile;
import com.amazon.aws.moa.objects.resources.v1_0.AccountNotificationQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountUserQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.UserProfileQuerySpecification;
import com.amazonaws.services.organizations.model.Account;

import edu.emory.moa.jmsobjects.identity.v1_0.DirectoryPerson;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.objects.resources.v1_0.DirectoryPersonQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentQuerySpecification;

/**
 *  An example object provider that maintains an in-memory
 *  store of UserNotifications.
 *
 * @author Steve Wheat (swheat@emory.edu)
 *
 */
public class EmoryUserNotificationProvider extends OpenEaiObject 
implements UserNotificationProvider {

	private Category logger = OpenEaiObject.logger;
	private AppConfig m_appConfig;
	private ProducerPool m_awsAccountServiceProducerPool = null;
	private ProducerPool m_directoryServiceProducerPool = null;
	private HashMap<String, List> m_userIdLists = new HashMap<String, List>();
	private String LOGTAG = "[EmoryUserNotificationProvider] ";
	private List<String> m_requiredEmailNotificationTypeList = null;
	private String m_accountSeries = null;
	private String m_emailFromAddress = null;
	private String m_emailOpening = null;
	private String m_emailClosing = null;
	
	/**
	 * @see UserNotificationProvider.java
	 */
	@Override
	public void init(AppConfig aConfig) throws ProviderException {
		logger.info(LOGTAG + "Initializing...");
		m_appConfig = aConfig;

		// Get the provider properties
		PropertyConfig pConfig = new PropertyConfig();
		try {
			pConfig = (PropertyConfig)aConfig
					.getObject("UserNotificationProviderProperties");
			setProperties(pConfig.getProperties());
		} 
		catch (EnterpriseConfigurationObjectException eoce) {
			String errMsg = "Error retrieving a PropertyConfig object from "
					+ "AppConfig: The exception is: " + eoce.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoce);
		}
		
		// Get a mail service from AppConfig.
		MailService ms = new MailService();
		try {
			ms = (MailService)aConfig
				.getObject("UserNotificationMailService");
			setMailService(ms);
		} 
		catch (EnterpriseConfigurationObjectException eoce) {
			String errMsg = "Error retrieving a PropertyConfig object from "
					+ "AppConfig: The exception is: " + eoce.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoce);
		}
		
		// Verify that required e-mail types are set.	
		Properties props = getProperties();
		String requiredEmailNotificationTypes = 
			props.getProperty("requiredEmailNotificationTypes");
		logger.info(LOGTAG + "Required e-mail types are: " + requiredEmailNotificationTypes);
		if (requiredEmailNotificationTypes == null ||
			requiredEmailNotificationTypes.equals("")) {
			String errMsg = "No required e-mail notification types " +
				"specified. Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
			
		// Set required e-mail Types.
		List<String> requiredEmailNotificationTypeList = new ArrayList();
		String[] requiredEmailNotificationTypeArray = 
			requiredEmailNotificationTypes.split(",");
		
		for (int i=0; i < requiredEmailNotificationTypeArray.length; i++) {
			String type = requiredEmailNotificationTypeArray[i];
			requiredEmailNotificationTypeList.add(type.trim());
		}
		logger.info(LOGTAG + "Required e-mail notification type list " +
			"has " + requiredEmailNotificationTypeList.size() + " types.");
		setRequiredEmailNotificationTypeList(requiredEmailNotificationTypeList);
		
		// Set the accountSeries
		String accountSeries = props.getProperty("accountSeries");
		setAccountSeries(accountSeries);
		logger.info(LOGTAG + "accountSeries is: " + getAccountSeries());
		
		// Set the emailOpening
		String emailOpening = props.getProperty("emailOpening");
		setEmailOpening(emailOpening);
		logger.info(LOGTAG + "emailOpening is: " + getEmailOpening());
				
		// Set the e-mailClosing
		String emailClosing = props.getProperty("emailClosing");
		setEmailClosing(emailClosing);
		logger.info(LOGTAG + "emailClosing is: " + getEmailClosing());
		
		// Set the emailFromAddress
		String emailFromAddress = props.getProperty("emailFromAddress");
		setEmailFromAddress(emailFromAddress);
		logger.info(LOGTAG + "emailFromAddress is: " + getEmailFromAddress());
	
		// This provider needs to send messages to the AWS account service
		// to create UserNotifications.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("AwsAccountServiceProducerPool");
			setAwsAccountServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		// This provider needs to send messages to the DirectoryService
		// to look up individual people.
		ProducerPool p2p2 = null;
		try {
			p2p2 = (ProducerPool)getAppConfig()
				.getObject("DirectoryServiceProducerPool");
			setDirectoryServiceProducerPool(p2p2);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}	
		
		logger.info(LOGTAG + pConfig.getProperties().toString());

		logger.info(LOGTAG + "Initialization complete.");
	}

	/**
	 * @see UserNotificationProvider.java
	 * 
	 * Note: this implementation returns a list of UserIds from properties.
	 */
	public List<String> getUserIdsForAccount(String accountId)
			throws ProviderException {

		// If the AccountId is null, throw an exception.
		if (accountId == null || accountId.equals("")) {
			String errMsg = "The accountId is null.";
			throw new ProviderException(errMsg);
		}
		
    	// Get a configured AccountUser and query spec from AppConfig
    	AccountUser accountUser = new AccountUser();
    	AccountUserQuerySpecification querySpec = new AccountUserQuerySpecification();
		try {
			accountUser = (AccountUser)m_appConfig
					.getObjectByType(accountUser.getClass().getName());
			querySpec = (AccountUserQuerySpecification)m_appConfig
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		// Set the values of the querySpec.
		try {
			querySpec.setAccountId(accountId);
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"query specification object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
    	
    	// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the AccountUsers for this account.
		List accountUserList = null;
		try {
			long startTime = System.currentTimeMillis();
			accountUserList = accountUser.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for AccountUser for account " +
				accountId + " objects in " + time + " ms. Returned " + 
				accountUserList.size() + " users.");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the " +
					"AccountUser objects The exception is: " + 
					eoqe.getMessage();
			logger.warn(LOGTAG + errMsg);
			
			// If there is a caches list of users, return it.
			if (getUserIdList(accountId) != null) {
				logger.warn(LOGTAG + "Returning cached AccountUser list.");
				return getUserIdList(accountId);
			}
			else {
				logger.error(LOGTAG + "No cached AccountUser list found.");
				throw new ProviderException(errMsg, eoqe);
			}
		}
		// In any case, release the producer back to the pool.
		finally {
			getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
		
		// Add UserIds to a list
		ArrayList<String> userIds = new ArrayList<String>();
		ListIterator li = accountUserList.listIterator();
		while (li.hasNext()) {
			AccountUser au = (AccountUser)li.next();
			userIds.add(au.getUserId());
		}
		
		setUserIdList(accountId, userIds);
		return userIds;
		
	}

	/**
	 * @see UserNotificationProvider.java
	 */
	public UserNotification generate(String userId, AccountNotification aNotification)
			throws ProviderException {

		// Get a configured UserNotification object from AppConfig
		UserNotification uNotification = new UserNotification();
		try {
			uNotification= (UserNotification)m_appConfig
				.getObjectByType(uNotification.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}

		// Set the values of the UserNotification.
		try {
			uNotification.setAccountNotificationId(aNotification.getAccountNotificationId());
			uNotification.setType(aNotification.getType());
			uNotification.setPriority(aNotification.getPriority());
			uNotification.setSubject(aNotification.getSubject());
			uNotification.setText(aNotification.getText());
			uNotification.setReferenceId(aNotification.getReferenceId());
			uNotification.setUserId(userId);
			uNotification.setRead("false");
			uNotification.setCreateUser("AwsAccountService");
			uNotification.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"Stack object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
		
		// Create the UserNotification in the AWS Account Service.
		// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Create the UserNotification object.
		try {
			long startTime = System.currentTimeMillis();
			uNotification.create(rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Created UserNotification " +
				"object in " + time + " ms.");
		}
		catch (EnterpriseObjectCreateException eoce) {
			String errMsg = "An error occurred creating the " +
					"UserNotification object The exception is: " + 
					eoce.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoce);
		}
		// In any case, release the producer back to the pool.
		finally {
			getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
		}
		
		// Return the object.
		return uNotification;
	}
	
	public void processAdditionalNotifications(UserNotification notification) 
		throws ProviderException {
		
		String userId = null;
		if (notification != null) {
			userId = notification.getUserId();
		}
		else {
			String errMsg = "UserNotification is null. Can't continue.";
			logger.error(errMsg);
			throw new ProviderException(errMsg);
		}
		
		String LOGTAG = "[EmoryUserNotificationProvider.processAdditionalNotifications] ";
		
		// Get the directory person for the user.
		DirectoryPerson dp = directoryPersonQuery(notification.getUserId());
		
		// If sendEmail is true, send the user an e-mail notification.
		// Otherwise, log that no e-mail is required.
		if (sendEmailNotification(notification, dp)) {
			logger.info(LOGTAG + "Sending e-mail for user " +
				dp.getKey() + "(" +
				dp.getFullName() + ")");
			MailService ms = getMailService();
			try {
				ms.setFromAddress(getEmailFromAddress());
				ms.setRecipientList(dp.getEmail().getEmailAddress());
			}
			catch (AddressException ae) {
				String errMsg = "An error occurred setting addresses on " +
					"the e-mail message. The exception is: " + ae.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, ae);
			}
			
			ms.setSubject("AWS at Emory " + getAccountSeries() + 
				" Notification: " + notification.getSubject());
			ms.setMessageBody(buildEmailMessageBody(notification, dp));
			long startTime = System.currentTimeMillis();
			logger.info(LOGTAG + "Sending e-mail message...");
			boolean sentMessage = ms.sendMessage();
			long time = System.currentTimeMillis() - startTime;
			if (sentMessage == true) {
				logger.info(LOGTAG + "Sent e-mail in " + time + " ms.");
			}
			else {
				String errMsg = "Failed to send e-mail.";
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg);
			}
		}
		else {
			logger.info(LOGTAG + "Will not send e-mail for user " +
				dp.getKey() + " (" +
				dp.getFullName() + ").");
		}
		
		return;
	}
	
	private AppConfig getAppConfig() {
		return m_appConfig;
	}
	
	private void setAwsAccountServiceProducerPool(ProducerPool pool) {
		m_awsAccountServiceProducerPool = pool;
	}
	
	private ProducerPool getAwsAccountServiceProducerPool() {
		return m_awsAccountServiceProducerPool;
	}
	
	private void setDirectoryServiceProducerPool(ProducerPool pool) {
		m_directoryServiceProducerPool = pool;
	}
	
	private ProducerPool getDirectoryServiceProducerPool() {
		return m_directoryServiceProducerPool;
	}
	
	private List getUserIdList(String accountId) {
		List userIdList = m_userIdLists.get(accountId);
		return userIdList;
	}
	
	private void setUserIdList (String accountId, List userIdList) {
		m_userIdLists.put(accountId, userIdList);
	}
	
	private void setAccountSeries(String accountSeries) {
		m_accountSeries = accountSeries;
	}
	
	private String getAccountSeries () {
		return m_accountSeries;
	}
	
	private void setEmailFromAddress(String emailFromAddress) {
		m_emailFromAddress = emailFromAddress;
	}
	
	private String getEmailFromAddress () {
		return m_emailFromAddress;
	}
	
	private void setEmailOpening(String emailOpening) {
		m_emailOpening = emailOpening;
	}
	
	private String getEmailOpening () {
		return m_emailOpening;
	}
	
	private void setEmailClosing(String emailClosing) {
		m_emailClosing = emailClosing;
	}
	
	private String getEmailClosing() {
		return m_emailClosing;
	}
	
	private boolean sendEmailNotification(UserNotification notification, DirectoryPerson dp)
		throws ProviderException {
		
		String LOGTAG = "[EmoryUserNotificationProvider.sendEmailnotification] ";
		boolean sendEmailNotification = false;
	
		// If the notification matches the list of e-mail required types,
		// return true. Otherwise, determine if the user prefers to 
		// receive e-mail notifications.
		if (isEmailRequired(notification)) {
			logger.info(LOGTAG + "An e-mail notification is required for " +
				"all notifications of type " + notification.getType() + ". " +
				"Sending e-mail notification to user " + dp.getKey() + " (" +
				dp.getFullName() + "). Sending e-mail.");
			return true;
		}
		else {
			// If they have a property called sendUserNotificationEmails with a
			// value of true, send them an e-mail. Otherwise log that no additional
			// notification methods were requested.
			if (sendUserNotificationEmails(dp.getKey()) == true) {
				logger.info(LOGTAG + "sendUserNotificationEmails property is " +
						"true for user " + dp.getKey() + " (" +
						dp.getFullName() + "). Should send e-mail.");
				sendEmailNotification = true;
			}
			else {
				logger.info(LOGTAG + "sendUserNotificationEmails property is " +
					"false for user " + dp.getKey() + "(" +
					dp.getFullName() + "). Will not send " +
					"e-mail.");
			}
		}
		
		return sendEmailNotification;	
	}
	
	private boolean isEmailRequired(UserNotification notification) {
		
		boolean isEmailRequired = false;
		
		// Build the list of override types from properties.
		List<String> types = getRequiredEmailNotificationTypeList();
		ListIterator li = types.listIterator();
		while(li.hasNext()) {
			String type = (String)li.next();
			if (type.equalsIgnoreCase(notification.getType())) {
				isEmailRequired = true;
			}
		}
		
		return isEmailRequired;
		
	}
	
	private void setRequiredEmailNotificationTypeList(List<String> types) {
		m_requiredEmailNotificationTypeList = types;
	}
	
	private List<String> getRequiredEmailNotificationTypeList() {
		return m_requiredEmailNotificationTypeList;
	}
	
	private boolean sendUserNotificationEmails(String userId)
		throws ProviderException {
		boolean sendUserNotificationEmails = false;
		
		UserProfile up = userProfileQuery(userId);
		List props = up.getProperty();
		ListIterator li = props.listIterator();
		while (li.hasNext()) {
			Property prop = (Property)li.next();
			if (prop.getKey().equalsIgnoreCase("sendUserNotificationEmails")) {
				if (prop.getValue().equalsIgnoreCase("true")) {
					sendUserNotificationEmails = true;
				}
			}
		}
		
		return sendUserNotificationEmails;
	}
	
	private String buildEmailMessageBody(UserNotification notification, DirectoryPerson dp)
		throws ProviderException {
		
		String messageBody = "Dear " + dp.getFullName() + ", \n\n";
		messageBody = messageBody + getEmailOpening() + "\n\n";
		
		String accountName = null;
		String accountId = null;
		String accountOwner = null;
		AccountNotification accountNotification = null;
		
		if (notification.getAccountNotificationId() != null) {
			accountNotification = 
				accountNotificationQuery(notification.getAccountNotificationId());
			com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account account = 
				accountQuery(accountNotification.getAccountId());
			accountName = account.getAccountName();
			accountId = account.getAccountId();
			String accountOwnerId = account.getAccountOwnerId();
			DirectoryPerson ownerDp = directoryPersonQuery(accountOwnerId);
			accountOwner = ownerDp.getFullName() + " (" + ownerDp.getKey() + ")";
		}
		
		DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
		Calendar cal = notification.getCreateDatetime().toCalendar();
		java.util.Date date = cal.getTime();
		String formattedCreateDatetime = dateFormat.format(date);
		
		messageBody = messageBody + "Notification Datetime: " + formattedCreateDatetime + "\n";
		if (accountId != null) {
			messageBody = messageBody + "Account: " + accountName + " (" + accountId + ")\n";
			messageBody = messageBody + "Account Owner: " + accountOwner + "\n";
		}
		messageBody = messageBody + "Type: " + notification.getType() + "\n";
		messageBody = messageBody + "Subject: " + notification.getSubject() + "\n\n";
		messageBody = messageBody + notification.getText() + "\n\n";
		
		messageBody = messageBody + "User Notification ID: " + 
				notification.getUserNotificationId() + "\n";
		
		if (notification.getReferenceId() != null) {
			messageBody = messageBody + "Reference ID: " + 
				notification.getReferenceId() + "\n";
		}
		
		if (accountNotification != null) {
			messageBody = messageBody + "Account Notification ID: " + 
				notification.getAccountNotificationId() + "\n";
		}
	
		messageBody = messageBody + "\n" + getEmailClosing();
		return messageBody;
	}
	
	private DirectoryPerson directoryPersonQuery(String userId) 
		throws ProviderException {
		
    	// Query the DirectoryService service for the user's
	    // DirectoryPerson object.
	
    	// Get a configured DirectoryPerson and
	    // DirectoryPersonQuerySpecification from AppConfig
		DirectoryPerson directoryPerson = new DirectoryPerson();
    	DirectoryPersonQuerySpecification querySpec = new DirectoryPersonQuerySpecification();
		try {
			directoryPerson = (DirectoryPerson)m_appConfig
				.getObjectByType(directoryPerson.getClass().getName());
			querySpec = (DirectoryPersonQuerySpecification)m_appConfig
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		// Set the values of the querySpec.
		try {
			querySpec.setKey(userId);
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"query specification object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
    	
    	// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getDirectoryServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the DirectoryPerson.
		List directoryPersonList = null;
		try {
			long startTime = System.currentTimeMillis();
			directoryPersonList = directoryPerson.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for DirectoryPerson for " +
				"userId " + userId + " in " + time + " ms. Returned " + 
				directoryPersonList.size() + " user(s) in the role.");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the " +
					"RoleAssignment objects The exception is: " + 
					eoqe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoqe);
		}
		// In any case, release the producer back to the pool.
		finally {
			getDirectoryServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
		
		if (directoryPersonList.size() == 0) {
			String errMsg = "Inappropriate number of DirectoryPerson " +
				"results. Expected 1 got " + directoryPersonList.size() +
				".";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		DirectoryPerson dp = (DirectoryPerson)directoryPersonList.get(0);
		return dp;
	}	
	
	private UserProfile userProfileQuery(String userId) 
	throws ProviderException {
	
	// Query the AwsAccountService service for the user's
    // UserProfile object.

	// Get a configured UserProfile and
    // UserProfileQuerySpecification from AppConfig
	UserProfile userProfile = new UserProfile();
	UserProfileQuerySpecification querySpec = new UserProfileQuerySpecification();
	try {
		userProfile = (UserProfile)m_appConfig
			.getObjectByType(userProfile.getClass().getName());
		querySpec = (UserProfileQuerySpecification)m_appConfig
			.getObjectByType(querySpec.getClass().getName());
	}
	catch (EnterpriseConfigurationObjectException ecoe) {
		String errMsg = "An error occurred retrieving an object from " +
				"AppConfig. The exception is: " + ecoe.getMessage();
		logger.error(LOGTAG + errMsg);
		throw new ProviderException(errMsg, ecoe);
	}
	
	// Set the values of the querySpec.
	try {
		querySpec.setUserId(userId);
	}
	catch (EnterpriseFieldException efe) {
		String errMsg = "An error occurred setting the values of the " +
			"query specification object. The exception is: " + 
			efe.getMessage();
		logger.error(LOGTAG + errMsg);
		throw new ProviderException(errMsg, efe);
	}
	
	// Get a RequestService to use for this transaction.
	RequestService rs = null;
	try {
		rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
	}
	catch (JMSException jmse) {
		String errMsg = "An error occurred getting a request service to use " +
			"in this transaction. The exception is: " + jmse.getMessage();
		logger.error(LOGTAG + errMsg);
		throw new ProviderException(errMsg, jmse);
	}
	// Query for the UserProfile.
	List userProfileList = null;
	try {
		long startTime = System.currentTimeMillis();
		userProfileList = userProfile.query(querySpec, rs);
		long time = System.currentTimeMillis() - startTime;
		logger.info(LOGTAG + "Queried for the UserProfile for " +
			"userId " + userId + " in " + time + " ms. Returned " + 
			userProfileList.size() + " user profile(s).");
	}
	catch (EnterpriseObjectQueryException eoqe) {
		String errMsg = "An error occurred querying for the " +
				"RoleAssignment objects The exception is: " + 
				eoqe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoqe);
	}
	// In any case, release the producer back to the pool.
	finally {
		getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
	}
	
	if (userProfileList.size() == 0) {
		return null;
	}
	else {
		UserProfile up = (UserProfile)userProfileList.get(0);
		return up;
	}
}	

	private com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account accountQuery(String accountId) 
		throws ProviderException {
		
    	// Query the AwsAccountService service for the account object.
	
    	// Get a configured Account and
	    // AccountQuerySpecification from AppConfig
		com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account account = new com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account();
    	AccountQuerySpecification querySpec = new AccountQuerySpecification();
		try {
			account =(com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account)
				m_appConfig.getObjectByType(account.getClass().getName());
			querySpec = (AccountQuerySpecification)m_appConfig
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		// Set the values of the querySpec.
		try {
			querySpec.setAccountId(accountId);
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"query specification object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
    	
    	// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the Account.
		List accountList = null;
		try {
			long startTime = System.currentTimeMillis();
			accountList = account.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for the Account for " +
				"accountId " + accountId + " in " + time + " ms. Returned " + 
				accountList.size() + " account(s).");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the " +
					"RoleAssignment objects The exception is: " + 
					eoqe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoqe);
		}
		// In any case, release the producer back to the pool.
		finally {
			getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
		
		if (accountList.size() == 0) {
			return null;
		}
		else {
			com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account a = 
				(com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account)accountList.get(0);
			return a;
		}
	}	

	private AccountNotification accountNotificationQuery(String accountNotificationId) 
		throws ProviderException {
		
    	// Query the AwsAccountService service for the account 
		// notificationobject.
	
    	// Get a configured AccountNotification and
	    // AccountNotificationQuerySpecification from AppConfig
		AccountNotification notification = new AccountNotification();
    	AccountNotificationQuerySpecification querySpec = 
    		new AccountNotificationQuerySpecification();
		try {
			notification =(AccountNotification)
				m_appConfig.getObjectByType(notification.getClass().getName());
			querySpec = (AccountNotificationQuerySpecification)m_appConfig
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		// Set the values of the querySpec.
		try {
			querySpec.setAccountNotificationId(accountNotificationId);
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"query specification object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
    	
    	// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the AccountNotification.
		List accountNotificationList = null;
		try {
			long startTime = System.currentTimeMillis();
			accountNotificationList = notification.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for the AccountNotification for " +
				"accountNotificationId " + accountNotificationId + " in " + time + " ms. Returned " + 
				accountNotificationList.size() + " account(s).");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the " +
					"RoleAssignment objects The exception is: " + 
					eoqe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoqe);
		}
		// In any case, release the producer back to the pool.
		finally {
			getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
		
		if (accountNotificationList.size() == 0) {
			return null;
		}
		else {
			AccountNotification an = (AccountNotification)accountNotificationList.get(0);
			return an;
		}
	}	

}
