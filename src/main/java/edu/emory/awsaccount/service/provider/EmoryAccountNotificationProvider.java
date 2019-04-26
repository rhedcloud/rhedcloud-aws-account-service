/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
// Java utilities
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;
import java.util.Random;
import java.util.StringTokenizer;

import javax.jms.JMSException;

import org.apache.commons.io.IOUtils;
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
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.EnterpriseObjectUpdateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.moa.objects.resources.Result;
import org.openeai.moa.objects.resources.v1_0.QueryLanguage;
import org.openeai.threadpool.ThreadPool;
import org.openeai.threadpool.ThreadPoolException;
import org.openeai.transport.RequestService;
import org.openeai.utils.filetransfer.handlers.TransferHandlerException;
import org.openeai.utils.lock.Key;
import org.openeai.utils.lock.Lock;
import org.openeai.utils.lock.LockAlreadySetException;
import org.openeai.utils.lock.LockException;
import org.openeai.utils.sequence.Sequence;
import org.openeai.utils.sequence.SequenceException;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountNotification;

//AWS Message Object API (MOA)

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.jmsobjects.user.v1_0.AccountUser;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountNotificationQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Annotation;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudProvisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import com.service_now.moa.jmsobjects.servicedesk.v2_0.Incident;
import com.service_now.moa.objects.resources.v2_0.IncidentRequisition;

import edu.emory.awsaccount.service.provider.step.Step;
import edu.emory.awsaccount.service.provider.step.StepException;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.jmsobjects.identity.v2_0.Person;
import edu.emory.moa.jmsobjects.network.v1_0.Cidr;
import edu.emory.moa.jmsobjects.network.v1_0.CidrAssignment;
import edu.emory.moa.jmsobjects.validation.v1_0.EmailAddressValidation;
import edu.emory.moa.objects.resources.v1_0.CidrRequisition;
import edu.emory.moa.objects.resources.v1_0.EmailAddressValidationQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentQuerySpecification;
import edu.emory.moa.objects.resources.v2_0.PersonQuerySpecification;

/**
 *  A provider for AccountNotifications that suppresses duplicate
 *  account notifications for the create action and passes all
 *  other actions through to a deployment of the RDBMS request
 *  command.
 *
 * @author Steve Wheat (swheat@emory.edu)
 *
 */
public class  EmoryAccountNotificationProvider extends OpenEaiObject 
implements AccountNotificationProvider {

	private Category logger = OpenEaiObject.logger;
	private AppConfig m_appConfig;
	private String m_primedDocUrl = null;
	private boolean m_verbose = false;
	private Sequence m_provisioningIdSequence = null;
	private Sequence m_accountSequence = null;
	private String m_centralAdminRoleDn = null;
	private ProducerPool m_awsAccountServiceProducerPool = null;
	private ProducerPool m_idmServiceProducerPool = null;
	private ProducerPool m_serviceNowServiceProducerPool = null;
	private ThreadPool m_threadPool = null;
	private int m_threadPoolSleepInterval = 1000;
	private String LOGTAG = "[EmoryAccountNotificationProvider] ";
	private int m_requestTimeoutIntervalInMillis = 10000;
	private int m_suppressionIntervalInMillis = 3600000;
	private boolean m_suppressNotifications = true;
	
	/**
	 * @see AccountNotificationProvider.java
	 */
	@Override
	public void init(AppConfig aConfig) throws ProviderException {
		logger.info(LOGTAG + "Initializing...");
		setAppConfig(aConfig);

		// Get the provider properties
		PropertyConfig pConfig = new PropertyConfig();
		try {
			pConfig = (PropertyConfig)aConfig
				.getObject("AccountNotificationProviderProperties");
		} 
		catch (EnterpriseConfigurationObjectException eoce) {
			String errMsg = "Error retrieving a PropertyConfig object from "
					+ "AppConfig: The exception is: " + eoce.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoce);
		}
		
		Properties props = pConfig.getProperties();
		setProperties(props);
		logger.info(LOGTAG + getProperties().toString());
		
		// Set the verbose property.
		setVerbose(Boolean.valueOf(getProperties().getProperty("verbose", "false")));
		logger.info(LOGTAG + "Verbose property is: " + getVerbose());
		
		// Set the suppressNotifications property.
		setSuppressNotifications(Boolean.valueOf(getProperties()
			.getProperty("suppressNotifications", "true")));
		logger.info(LOGTAG + "suppressNotifications property is: " + getSuppressNotifications());
		
		// Set the suppressionInterval property.
		String sInterval = getProperties()
			.getProperty("suppressionIntervalInMillis", "3600000");
		setSuppressionIntervalInMillis(Integer.parseInt(sInterval));
		logger.info(LOGTAG + "suppressionIntervalInMillis is: " +
			getSuppressionIntervalInMillis());
		
		// Set the requestTimeoutInterval property.
		String tInterval = getProperties()
			.getProperty("requestTimeoutIntervalInMillis", "10000");
		setRequestTimeoutIntervalInMillis(Integer.parseInt(tInterval));
		logger.info(LOGTAG + "requestTimeoutIntervalInMillis is: " +
			getRequestTimeoutIntervalInMillis());
		
		// This provider needs to send messages to the AWS account service
		// to initialize provisioning transactions.
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
		
		logger.info(LOGTAG + "Initialization complete.");
	}

	/**
	 * @see AccountNotificationProvider.java
	 * 
	 * This method proxys a query to an RDBMS command that handles it. The 
	 * purpose of including this operation in this command (and not just the
	 * generate) operations is that it will give us one command that should
	 * handle all broad access to the AccountNotification service operations.
	 */
	public List<AccountNotification> 
		query(AccountNotificationQuerySpecification querySpec)
			throws ProviderException {
			String LOGTAG = "[EmoryAccountNotificationProvider.query] ";
			logger.info(LOGTAG + "Querying for AccountNotification.");
		
			// Get a configured AccountNotification object to use.
			AccountNotification aNotification = new AccountNotification();
			try {
				aNotification = (AccountNotification)getAppConfig()
					.getObjectByType(aNotification.getClass().getName());
			}
			catch (EnterpriseConfigurationObjectException ecoe) {
				String errMsg = "An error occurred getting an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException();
			}

			// Get a RequestService to use for this transaction.
			RequestService rs = null;
			try {
				rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
				PointToPointProducer p2p = (PointToPointProducer)rs;
				p2p.setRequestTimeoutInterval(getRequestTimeoutIntervalInMillis());
			}
			catch (JMSException jmse) {
				String errMsg = "An error occurred getting a request service to use " +
					"in this transaction. The exception is: " + jmse.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, jmse);
			}
			// Query for AccountNotification.
			List results = null;
			try {
				logger.info(LOGTAG + "Querying for AccountNotification...");
				long startTime = System.currentTimeMillis();
				results = aNotification.query(querySpec, rs);
				long time = System.currentTimeMillis() - startTime;
				logger.info(LOGTAG + "Queried for AccountNotification " +
					"objects in " + time + " ms. Found " + results.size() +
					" results.");
			}
			catch (EnterpriseObjectQueryException eoce) {
				String errMsg = "An error occurred querying the Account" +
					"Notification object The exception is: " + 
					eoce.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new ProviderException(errMsg, eoce);
			}
			// In any case, release the producer back to the pool.
			finally {
				getAwsAccountServiceProducerPool()
					.releaseProducer((PointToPointProducer)rs);
			}
			
			// Return the results
			return results;
	}
	
	/**
	 * @see AccountNotificationProvider.java
	 */
	public void create(AccountNotification aNotification) 
		throws ProviderException {
		String LOGTAG = "[EmoryAccountNotificationProvider.create] ";
		
		logger.info(LOGTAG + "Evaluating AccountNotification for create action...");
		
		// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			logger.info(LOGTAG + "Getting an exclusive producer for the AWS Account Service...");
			rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		
		// TODO: query to determine if a notification has already been created in
		// the last suppressionInterval milliseconds.
		
		// Get a configured AccountNotificationQuerySpecification to use.
		logger.info(LOGTAG + "Getting a configured query spec from AppConfig...");
		AccountNotificationQuerySpecification querySpec = 
				new AccountNotificationQuerySpecification();
		try {
			querySpec = (AccountNotificationQuerySpecification)getAppConfig()
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred getting an object from " +
				"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException();
		}
		
		// Get the annotation text 
		logger.info(LOGTAG + "Getting the annotation text...");
		List<Annotation> aList = aNotification.getAnnotation();
		ListIterator li = aList.listIterator();
		String annotationText = null;
		while (li.hasNext()) {
			Annotation annotation = (Annotation)li.next();
			if (annotation.getText().contains("SRDOBJECT")) {
				logger.info(LOGTAG + "SecurityRiskDetection Annotation is: " +
					annotation.getText());
				annotationText = annotation.getText();
			}
		}
		
		logger.info(LOGTAG + "Setting the values of the query spec...");
		long endTime = System.currentTimeMillis();
		long startTime = endTime - getSuppressionIntervalInMillis();
		try {
			querySpec.setStartCreateDatetime(new Datetime("StartCreate", startTime));
			querySpec.setEndCreateDatetime(new Datetime("EndCreate", endTime));
			querySpec.setAnnotationText(annotationText);
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting a field value " +
				"on the query specification. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException();
		}
		
		// Convert the query spec to an XML string.
		try {
			String xmlQuerySpec = querySpec.toXmlString();
			logger.info(LOGTAG + "The query spec is: " + xmlQuerySpec);
		}
		catch (XmlEnterpriseObjectException xeoe) {
			String errMsg = "An error occurred serializing the query " +
				"spec to an XML string. The exception is: " + 
				xeoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException();
		}
		
		// Query for any notifications during the suppression interval
		logger.info(LOGTAG + "Querying any notifications during the " +
			"suppression interval");
		long queryStartTime = System.currentTimeMillis();
		List<AccountNotification> results = query(querySpec);
		long queryTime = System.currentTimeMillis() - queryStartTime;
		logger.info(LOGTAG + "Queried for AccountNotifications in the " +
			"suppression interval in " + queryTime + " ms. Found " +
			results.size() + " result(s)");
		
		boolean suppressNotification = false;
		if (results.size() > 0) {
			logger.info(LOGTAG + "There are AccountNotifications in the " +
				"suppression interval, setting suppressNotification to true.");
			suppressNotification = true;
		}
		
		// If suppress is true, log it, do not create a new AccountNotification, 
		// but update the most recent account notification to indicate that another
		// notification was dropped.
		if (suppressNotification == true) {
			
			String notification = null;
			try {
				notification = aNotification.toXmlString();
			}
			catch (XmlEnterpriseObjectException xeoe) {
				String errMsg = "An error occurred serializing an " +
					"object to an XML string. The exception is: " + 
					xeoe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException();
			}
			
			logger.info(LOGTAG + "suppressNotification is true, will not create " +
				"AccountNotification: " + notification);
		}
		// Otherwise, create the AccountNotification
		else {
			try {
				long createStartTime = System.currentTimeMillis();
				aNotification.create(rs);
				long time = System.currentTimeMillis() - createStartTime;
				logger.info(LOGTAG + "Created AccountNotification " +
					"object in " + time + " ms.");
			}
			catch (EnterpriseObjectCreateException eoce) {
				String errMsg = "An error occurred creating the " +
					"AccountNotification object The exception is: " + 
						eoce.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new ProviderException(errMsg, eoce);
			}
			// In any case, release the producer back to the pool.
			finally {
				getAwsAccountServiceProducerPool()
					.releaseProducer((PointToPointProducer)rs);
			}
		}
		
		return;
	}

	/**
	 * @see AccountNotificationProvider.java
	 */
	public void update(AccountNotification aNotification) throws ProviderException {		
		String LOGTAG = "[EmoryAccountNotificationProvider.update] ";
		
		// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool()
				.getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Update the AccountNotification
		Result result = null;
		try {
			long startTime = System.currentTimeMillis();
			result = (Result)aNotification.update(rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Updated AccountNotification " +
				"object in " + time + " ms.");
		}
		catch (EnterpriseObjectUpdateException eoce) {
			List<org.openeai.moa.objects.resources.Error> errors = result.getError();
			String errList = "";
			if (errors != null) {
				ListIterator li = errors.listIterator();
				while (li.hasNext()) {
					org.openeai.moa.objects.resources.Error error = 
						(org.openeai.moa.objects.resources.Error)li.next();
					errList = errList + error.getErrorNumber() + ": " + 
						error.getErrorDescription() + " ";
				}
			}
			String errMsg = "An error occurred updating the " +
				"AccountNotification object The exception is: " + 
				eoce.getMessage() + "The error list is: " + errList;
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoce);
		}
		// In any case, release the producer back to the pool.
		finally {
			getAwsAccountServiceProducerPool()
				.releaseProducer((PointToPointProducer)rs);
		}
	}
	
	/**
	 * @see AccountNotificationProvider.java
	 */
	public void delete(AccountNotification aNotification) throws ProviderException {		
		String LOGTAG = "[EmoryAccountNotificationProvider.delete] ";
		
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
		// Delete the AccountNotification
		try {
			long startTime = System.currentTimeMillis();
			aNotification.delete("Delete", rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Deleted AccountNotification " +
				"object in " + time + " ms.");
		}
		catch (EnterpriseObjectDeleteException eode) {
			String errMsg = "An error occurred deleting the " +
					"AccountNotification object The exception is: " + 
					eode.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eode);
		}
		// In any case, release the producer back to the pool.
		finally {
			getAwsAccountServiceProducerPool()
				.releaseProducer((PointToPointProducer)rs);
		}
	}
	
	/**
	 * @param boolean, the verbose logging property
	 * <P>
	 * This method sets the verbose logging property
	 */
	private void setVerbose(boolean verbose) {
		m_verbose = verbose;
	}

	/**
	 * @return boolean, the verbose logging property
	 * <P>
	 * This method returns the verbose logging property
	 */
	private boolean getVerbose() {
		return m_verbose;
	}
	
	
    /**
     * @param ProducerPool, the AWS account service producer pool.
     *            <P>
     *            This method sets the producer pool to use to send 
     *            messages to the AWS Account Service.
     */
    private void setAwsAccountServiceProducerPool(ProducerPool pool) {
        m_awsAccountServiceProducerPool = pool;
    }

    /**
     * @return ProducerPool, the AWS account service producer pool.
     *         <P>
     *         This method returns a reference to the producer pool to use to
     *         send messages to the AWS account service.
     */
    private ProducerPool getAwsAccountServiceProducerPool() {
        return m_awsAccountServiceProducerPool;
    }
    
    /**
     * @param ProducerPool, the ServiceNow service producer pool.
     *            <P>
     *            This method sets the producer pool to use to send 
     *            messages to the ServiceNow Service.
     */
    private void setServiceNowServiceProducerPool(ProducerPool pool) {
        m_serviceNowServiceProducerPool = pool;
    }

    /**
     * @return ProducerPool, the ServiceNow service producer pool.
     *         <P>
     *         This method returns a reference to the producer pool to use to
     *         send messages to the ServiceNow service.
     */
    private ProducerPool getServiceNowServiceProducerPool() {
        return m_serviceNowServiceProducerPool;
    }
	
    /**
     * @param AppConfig
     *            , the AppConfig object of this provider.
     *            <P>
     *            This method sets the AppConfig object for this provider to
     *            use.
     */
    private void setAppConfig(AppConfig aConfig) {
        m_appConfig = aConfig;
    }
    
    /**
     * @return AppConfig, the AppConfig of this provider.
     *         <P>
     *         This method returns a reference to the AppConfig this provider is
     *         using.
     */
    private AppConfig getAppConfig() {
        return m_appConfig;
    }
    
    private void setRequestTimeoutIntervalInMillis(int time) {
		m_requestTimeoutIntervalInMillis = time;
	}
	
	private int getRequestTimeoutIntervalInMillis() {
		return m_requestTimeoutIntervalInMillis;
	}
	
	private void setSuppressionIntervalInMillis(int time) {
		m_suppressionIntervalInMillis = time;
	}
	
	private int getSuppressionIntervalInMillis() {
		return m_suppressionIntervalInMillis;
	}
	
	private void setSuppressNotifications(boolean suppressNotifications) {
		m_suppressNotifications = suppressNotifications;
	}
	
	private boolean getSuppressNotifications() {
		return m_suppressNotifications;
	}
	
	public Incident generateIncident(IncidentRequisition req) 
		throws ProviderException {
		
		String LOGTAG = "[EmoryAccountNotificationProvider.generateIncident] ";
		
		if (req == null) {
			String errMsg = "IncidentRequisision is null. " + 
				"Can't generate an Incident.";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}

		// Get a configured Incident object from AppConfig.
		Incident incident = new Incident();
	    try {
	    	incident = (Incident)getAppConfig()
		    		.getObjectByType(incident.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new ProviderException(errMsg, ecoe);
	    }
	    
	    // Log the state of the requisition.
	    try {
	    	logger.info(LOGTAG + "Incident requisition is: " + req.toXmlString());
	    }
	    catch (XmlEnterpriseObjectException xeoe) {
	    	String errMsg = "An error occurred serializing the requisition " +
	  	    	  "to XML. The exception is: " + xeoe.getMessage();
  	    	logger.error(LOGTAG + errMsg);
  	    	throw new ProviderException(errMsg, xeoe);
	    }    
		
		// Get a producer from the pool
		RequestService rs = null;
		try {
			rs = (RequestService)getServiceNowServiceProducerPool()
				.getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a producer " +
				"from the pool. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
	    
		List results = null;
		try { 
			long generateStartTime = System.currentTimeMillis();
			results = incident.generate(req, rs);
			long generateTime = System.currentTimeMillis() - generateStartTime;
			logger.info(LOGTAG + "Generated Incident in " +
				+ generateTime + " ms. Returned " + results.size() + 
				" result.");
		}
		catch (EnterpriseObjectGenerateException eoge) {
			String errMsg = "An error occurred generating the  " +
	    	  "Incident object. The exception is: " + eoge.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new ProviderException(errMsg, eoge);
		}
		finally {
			// Release the producer back to the pool
			getServiceNowServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		
		return (Incident)results.get(0);
	}
	
	private String parseUserId(String dn) {
		StringTokenizer st1 = new StringTokenizer(dn, ",");
		String firstToken = st1.nextToken();
		StringTokenizer st2 = new StringTokenizer(firstToken, "=");
		st2.nextToken();
		String userId = st2.nextToken();
		return userId;
	}
	
	private void createUserNotification (UserNotification notification)
		throws ProviderException {
		
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
			notification.create(rs);
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
	}
}		
	
