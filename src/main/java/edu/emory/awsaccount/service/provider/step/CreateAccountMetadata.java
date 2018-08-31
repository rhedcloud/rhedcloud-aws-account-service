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

import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.moa.objects.resources.Result;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountProvisioningAuthorization;
import com.amazon.aws.moa.objects.resources.v1_0.AccountProvisioningAuthorizationQuerySpecification;
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
public class CreateAccountMetadata extends AbstractStep implements Step {
	
	private ProducerPool m_awsAccountServiceProducerPool = null;
	private String m_passwordLocation = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[CreateAccountMetadata.init] ";
		
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
		
		String passwordLocation = getProperties().getProperty("passwordLocation", null);
		setPasswordLocation(passwordLocation);
		logger.info(LOGTAG + "passwordLocation is: " + getPasswordLocation());
		
		logger.info(LOGTAG + "Initialization complete.");
		
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[CreateAccountMetadata.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean accountMetadataCreated = false;
		
		// Return properties
		List<Property> props = new ArrayList<Property>();
		props.add(buildProperty("stepExecutionMethod", RUN_EXEC_TYPE));
		
		// Get the allocatedNewAccount property from the
		// GENERATE_NEW_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step1 = getProvisioningStepByType("GENERATE_NEW_ACCOUNT");
		boolean allocatedNewAccount = false;
		String newAccountId = null;
		String newAccountName = null;
		String accountEmailAddress = null;
		if (step1 != null) {
			logger.info(LOGTAG + "Step GENERATE_NEW_ACCOUNT found.");
			String sAllocatedNewAccount = getResultProperty(step1, "allocatedNewAccount");
			allocatedNewAccount = Boolean.parseBoolean(sAllocatedNewAccount);
			props.add(buildProperty("allocatedNewAccount", Boolean.toString(allocatedNewAccount)));
			logger.info(LOGTAG + "Property allocatedNewAccount from preceding " +
				"step is: " + allocatedNewAccount);
			newAccountId = getResultProperty(step1, "newAccountId");
			props.add(buildProperty("newAccountId", newAccountId));
			logger.info(LOGTAG + "Property newAccountId from preceding " +
				"step is: " + newAccountId);
			newAccountName = getResultProperty(step1, "newAccountName");
			props.add(buildProperty("newAccountName", newAccountName));
			logger.info(LOGTAG + "Property newAccountName from preceding " +
				"step is: " + newAccountName);
			accountEmailAddress = getResultProperty(step1, "accountEmailAddress");
			props.add(buildProperty("accountEmailAddress", accountEmailAddress));
			logger.info(LOGTAG + "Property accountEmailAddress from preceding " +
				"step is: " + accountEmailAddress);
			
		}
		else {
			String errMsg = "Step GENERATE_NEW_ACCOUNT not found. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
			
		// If allocatedNewAccount is true and newAccountId is not null, 
		// Send an Account.Create-Request to the AWS Account service.
		if (allocatedNewAccount && newAccountId != null) {
			logger.info(LOGTAG + "allocatedNewAccount is true and newAccountId " + 
				"is not null. Sending an Account.Create-Request to create account " +
				"metadata.");
			
			// Get a configured account object from AppConfig.
			Account account = new Account();
		    try {
		    	account = (Account)getAppConfig()
			    	.getObjectByType(account.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
		    
		    // Get the VPCP requisition object.
		    VirtualPrivateCloudRequisition req = getVirtualPrivateCloudProvisioning()
		    	.getVirtualPrivateCloudRequisition();
		    
		    // Set the values of the account.
		    try {
		    	account.setAccountId(newAccountId);
		    	account.setAccountName(newAccountName);
		    	account.setAccountOwnerId(req.getAccountOwnerUserId());
		    	account.setComplianceClass(req.getComplianceClass());
		    	account.setPasswordLocation(getPasswordLocation());
		    	account.setFinancialAccountNumber(req.getFinancialAccountNumber());
		    	
		    	EmailAddress primaryEmailAddress = account.newEmailAddress();
		    	primaryEmailAddress.setType("primary");
		    	primaryEmailAddress.setEmail(accountEmailAddress);
		    	account.addEmailAddress(primaryEmailAddress);
		    	
		    	EmailAddress operationsEmailAddress = account.newEmailAddress();
		    	operationsEmailAddress.setType("operations");
		    	operationsEmailAddress.setEmail(accountEmailAddress);
		    	account.addEmailAddress(operationsEmailAddress);
		    	
		    	account.setCreateUser(req.getAuthenticatedRequestorUserId());
		    	Datetime createDatetime = new Datetime("Create", 
		    		System.currentTimeMillis());
		    	account.setCreateDatetime(createDatetime);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "query spec. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the account.
		    try {
		    	logger.info(LOGTAG + "Account to create is: " + account.toXmlString());
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
				account.create(rs);
				long createTime = System.currentTimeMillis() - startTime;
				logger.info(LOGTAG + "Create Account in " + createTime +
					" ms.");
				accountMetadataCreated = true;
				props.add(buildProperty("allocatedNewAccount", 
					Boolean.toString(allocatedNewAccount)));
				props.add(buildProperty("createdAccountMetadata", 
					Boolean.toString(accountMetadataCreated)));
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
			}
			
		}
		// If allocatedNewAccount is false, log it and add result props.
		else {
			logger.info(LOGTAG + "allocatedNewAccount is false. " +
				"no need to create account metadata.");
			props.add(buildProperty("allocatedNewAccount", 
				Boolean.toString(allocatedNewAccount)));
			props.add(buildProperty("createdAccountMetadata", 
				"not applicable"));
		}
		
		// Update the step result.
		String stepResult = FAILURE_RESULT;
		if (accountMetadataCreated == true && allocatedNewAccount == true) {
			stepResult = SUCCESS_RESULT;
		}
		if (allocatedNewAccount == false) {
			stepResult = SUCCESS_RESULT;
		}
		
		// Update the step.
		update(COMPLETED_STATUS, stepResult, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[CreateAccountMetadata.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
    	props.add(buildProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE));
    	Property prop = buildProperty("accountSequenceNumber", "10000");
		
		// Update the step.
    	update(COMPLETED_STATUS, SUCCESS_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
	}
	
	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[CreateAccountMetadata.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
    	props.add(buildProperty("stepExecutionMethod", FAILURE_EXEC_TYPE));
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
	}
	
	public void rollback() throws StepException {
		
		super.rollback();
		
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[CreateAccountMetadata.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT, getResultProperties());
		
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
	
	private void setPasswordLocation (String loc) throws 
		StepException {

		if (loc == null) {
			String errMsg = "passwordLocation property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_passwordLocation = loc;
	}

	private String getPasswordLocation() {
		return m_passwordLocation;
	}
	
}
