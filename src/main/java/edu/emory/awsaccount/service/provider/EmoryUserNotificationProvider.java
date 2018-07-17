/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

// Java utilities
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;

import javax.jms.JMSException;

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
import org.openeai.config.PropertyConfig;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.transport.RequestService;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

//AWS Message Object API (MOA)
import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountNotification;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;

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
	private String LOGTAG = "[EmoryUserNotificationProvider] ";
	
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
		
		// Get the list of UserIds from the properties.
		String strUserIds = getProperties().getProperty(accountId);
		List<String> userIds = Arrays.asList(strUserIds.split("\\s*,\\s*"));
		return userIds;
		
		// TODO: Query the AWS Account Service for all users associated with this account.
		// Build a list of UserIds and return it.
		
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
	
	private AppConfig getAppConfig() {
		return m_appConfig;
	}
	
	private void setAwsAccountServiceProducerPool(ProducerPool pool) {
		m_awsAccountServiceProducerPool = pool;
	}
	
	private ProducerPool getAwsAccountServiceProducerPool() {
		return m_awsAccountServiceProducerPool;
	}

}
