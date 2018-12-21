/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.jms.JMSException;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jdom.Document;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.moa.objects.resources.Result;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountNotification;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountProvisioningAuthorization;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.AccountProvisioningAuthorizationQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Annotation;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.EmailAddress;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * If this is a new account request, create account metadata
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 30 August 2018
 **/
public class NotifyAdmins extends AbstractStep implements Step {
	
	private ProducerPool m_awsAccountServiceProducerPool = null;
	private String m_notificationTemplate;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[NotifyAdmins.init] ";
		
		// This step needs to send messages to the AWS account service
		// to create account metadata.
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
			throw new StepException(errMsg);
		}
/**		
		String notificationTemplate = getProperties()
			.getProperty("notificationTemplate", null);
		setNotificationTemplate(notificationTemplate);
		logger.info(LOGTAG + "notificationTemplate is: " +
			getNotificationTemplate());
		
		logger.info(LOGTAG + "Initialization complete.");
**/		
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[NotifyAdmins.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean sentNotification = false;
		
		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		
		// Get the VirtualPrivateCloudRequisition object.
	    VirtualPrivateCloudProvisioning vpcp = getVirtualPrivateCloudProvisioning();
	    VirtualPrivateCloudRequisition req = vpcp.getVirtualPrivateCloudRequisition();
	    
		// Get the allocatedNewAccount property from the
		// GENERATE_NEW_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step1 = getProvisioningStepByType("GENERATE_NEW_ACCOUNT");
		String accountId = null;
		String newAccountId = null;
		
		newAccountId = getStepPropertyValue("getVirtualPrivateCloudProvisioning()",
			"newAccountId");
		addResultProperty("newAccountId", newAccountId);
		logger.info(LOGTAG + "Property newAccountId from preceding " +
			"step is: " + newAccountId);
		
		// If the newAccountId is null, get the accountId from the
		// VPCP requisition.
		if (newAccountId == null || newAccountId.equalsIgnoreCase("null")) {
			accountId = req.getAccountId();
			logger.info(LOGTAG + "newAccountId is null, getting the accountId " +
				"from the requisition object: " + accountId);
		}
		
		if (accountId == null || newAccountId.equalsIgnoreCase("null")) {
			String errMsg = "accountId is null. Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// Get a configured account notification object from AppConfig.
		AccountNotification aNotification = new AccountNotification();
	    try {
	    	aNotification = (AccountNotification)getAppConfig()
		    	.getObjectByType(aNotification.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, ecoe);
	    }
	    
	    // Set the values of the account.
	    try {
	    	aNotification.setAccountId(accountId);
	    	aNotification.setType("Provisioning");
	    	aNotification.setText(getNotificationText(req));
	    	aNotification
	    		.setCreateUser(req.getAuthenticatedRequestorUserId());
	    	Datetime createDatetime = new Datetime("Create", 
	    		System.currentTimeMillis());
	    	aNotification.setCreateDatetime(createDatetime);
	    	
	    	// Set the account to be SRD exempt initially.
	    	// This will be changed later in the provisioning.
	    	Annotation annotation = aNotification.newAnnotation();
	    	annotation.setText("AwsAccountService Provisioning");
	    	aNotification.addAnnotation(annotation);
	    }
	    catch (EnterpriseFieldException efe) {
	    	String errMsg = "An error occurred setting the values of the " +
	  	    	  "query spec. The exception is: " + efe.getMessage();
	  	    logger.error(LOGTAG + errMsg);
	  	    throw new StepException(errMsg, efe);
	    }
	    
	    // Log the state of the account.
	    try {
	    	logger.info(LOGTAG + "AccountNotification to create is: "
	    		+ aNotification.toXmlString());
	    }
	    catch (XmlEnterpriseObjectException xeoe) {
	    	String errMsg = "An error occurred serializing the query spec " +
	  	    	  "to XML. The exception is: " + xeoe.getMessage();
  	    	logger.error(LOGTAG + errMsg);
  	    	throw new StepException(errMsg, xeoe);
	    }    
		
		// Get a producer from the pool
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool()
				.getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a producer " +
				"from the pool. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, jmse);
		}
	    
		try { 
			long createStartTime = System.currentTimeMillis();
			aNotification.create(rs);
			long createTime = System.currentTimeMillis() - createStartTime;
			logger.info(LOGTAG + "Create Account in " + createTime +
				" ms.");
			sentNotification = true;
			addResultProperty("sentNotification", 
				Boolean.toString(sentNotification));
		}
		catch (EnterpriseObjectCreateException eoce) {
			String errMsg = "An error occurred creating the object. " +
	    	  "The exception is: " + eoce.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, eoce);
		}
		finally {
			// Release the producer back to the pool
			getAwsAccountServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
			
			// Update the step.
			update(COMPLETED_STATUS, SUCCESS_RESULT);
	    	
	    	// Log completion time.
	    	long time = System.currentTimeMillis() - startTime;
	    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
	    	
	    	// Return the properties.
	    	return getResultProperties();
		}	
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[NotifyAdmins.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
    	addResultProperty("accountMetadataCreated", "true");
		
		// Update the step.
    	update(COMPLETED_STATUS, SUCCESS_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[NotifyAdmins.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	public void rollback() throws StepException {
		
		super.rollback();
		
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[NotifyAdmins.rollback] ";
		logger.info(LOGTAG + "Rollback called, nothing to roll back.");
		
		addResultProperty("adminNotificationRollback", "not applicable");
		
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setAwsAccountServiceProducerPool(ProducerPool pool) {
		m_awsAccountServiceProducerPool = pool;
	}
	
	private ProducerPool getAwsAccountServiceProducerPool() {
		return m_awsAccountServiceProducerPool;
	}
	
	private void setNotificationTemplate (String template) throws 
		StepException {

		if (template == null) {
			String errMsg = "notificationTemplate property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_notificationTemplate = template;
	}

	private String getNotificationTemplate() {
		return m_notificationTemplate;
	}
	
	private String getNotificationText(VirtualPrivateCloudRequisition req) 
		throws StepException {
		
		String text = "";
		
		text = text + "Dear UserFirstName UserLastName,\n\n";
		text = text + "Your recent request for a Virtual Private Cloud in the AWS@Emory Service has been provisioned successfully. \n\n";
		text = text + "To log into your account and for detailed instructions visit https://dev.aws.emory.edu. ";
		text = text + "Please note that your new site-to-site VPN connection between Emory and AWS may take some time to initialize. You can check the status of your site-to-site VPN connection by logging into the VPCP Console at https://dev.vpcp.emory.edu and checking the status of your VPN connection on the VPC tab.\n\n";
		text = text + "The details of your request are: \n\n";
		
		String request = "";
		try {
			request = req.toXmlString();
		}
		catch (XmlEnterpriseObjectException xeoe) {
			String errMsg = "An error occurred serializing the object to XML. "
				+ "The exception is: " + xeoe.getMessage();
			logger.error(getStepTag() + errMsg);
			throw new StepException(errMsg, xeoe);
		}
		text = text + request;
				
		return text;
	}
	
}
