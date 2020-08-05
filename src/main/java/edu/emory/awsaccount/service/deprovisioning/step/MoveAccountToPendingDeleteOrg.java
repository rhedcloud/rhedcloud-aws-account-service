/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2020 RHEDcloud Foundation. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.deprovisioning.step;

import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import javax.jms.JMSException;

import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountDeprovisioning;
import com.amazon.aws.moa.objects.resources.v1_0.AccountDeprovisioningRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.organizations.AWSOrganizationsClient;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.ListAccountsForParentRequest;
import com.amazonaws.services.organizations.model.ListAccountsForParentResult;
import com.amazonaws.services.organizations.model.ListAccountsRequest;
import com.amazonaws.services.organizations.model.ListAccountsResult;
import com.amazonaws.services.organizations.model.MoveAccountRequest;
import com.amazonaws.services.organizations.model.MoveAccountResult;

import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;

/**
 * Move the account to the pending delete organizational unit.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 5 August 2020
 **/
public class MoveAccountToPendingDeleteOrg extends AbstractStep implements Step {
	
	private String m_accessKey = null;
	private String m_secretKey = null;
	private String m_sourceParentId = null;
	private String m_rootSourceParentId = null;
	private String m_standardSourceParentId = null;
	private String m_hipaaSourceParentId = null;
	private String m_destinationParentId = null;
	private ProducerPool m_awsAccountServiceProducerPool = null;
	
	private AWSOrganizationsClient m_awsOrganizationsClient = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, AccountDeprovisioningProvider adp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, adp);
		
		String LOGTAG = getStepTag() + "[MoveAccountToPendingDeleteOrg.init] ";
		
		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");
		
		String accessKey = getProperties().getProperty("accessKey", null);
		setAccessKey(accessKey);
		logger.info(LOGTAG + "accessKey is: " + getAccessKey());
		
		String secretKey = getProperties().getProperty("secretKey", null);
		setSecretKey(secretKey);
		logger.info(LOGTAG + "secretKey is: present");
		
		String rootSourceParentId = getProperties().getProperty("rootSourceParentId", null);
		setRootSourceParentId(rootSourceParentId);
		logger.info(LOGTAG + "rootSourceParentId is: " + getRootSourceParentId());
		
		String standardSourceParentId = getProperties().getProperty("standardSourceParentId", null);
		setStandardSourceParentId(standardSourceParentId);
		logger.info(LOGTAG + "standardSourceParentId is: " + getStandardSourceParentId());
		
		String hipaaSourceParentId = getProperties().getProperty("hipaaSourceParentId", null);
		setHipaaSourceParentId(hipaaSourceParentId);
		logger.info(LOGTAG + "hipaaSourceParentId is: " + getHipaaSourceParentId());
		
		String destinationParentId = getProperties().getProperty("destinationParentId", null);
		setDestinationParentId(destinationParentId);
		logger.info(LOGTAG + "destinationParentId is: " + getDestinationParentId());
	
		
		// Set the AWS account credentials
		BasicAWSCredentials creds = new BasicAWSCredentials(accessKey, 
			secretKey);
		
		// Instantiate an AWS client builder
		AWSOrganizationsClientBuilder builder = AWSOrganizationsClientBuilder
				.standard().withCredentials(new AWSStaticCredentialsProvider(creds));
		builder.setRegion("us-east-1");
		
		// Initialize the AWS client
		logger.info("Initializing AmazonCloudFormationClient...");
		AWSOrganizationsClient client = (AWSOrganizationsClient)builder.build();
		logger.info("AWSOrganizationsClient initialized.");
		ListAccountsRequest request = new ListAccountsRequest();
		
		// Perform a test query
		ListAccountsResult result = client.listAccounts(request);
		logger.info(LOGTAG + "List accounts result: " + result.toString());
		
		// Set the client
		setAwsOrganizationsClient(client);
		
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
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[MoveAccountToPendingDeleteOrg.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean accountMoved = false;
		
		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		
		// Get the AccountDeprovisinoingRequisition object.
	    AccountDeprovisioning ad = getAccountDeprovisioning();
	    AccountDeprovisioningRequisition req = ad.getAccountDeprovisioningRequisition();
	    
		// Get the accountId.
		String accountId = req.getAccountId();
		addResultProperty("accountId", accountId);
		
		// Query for the account metadata to get the compliance class.
		Account account = queryForAccount(accountId);
		if (account == null) {
			String msg = "No account metadata found for accountId " + accountId
				+ ". Cannot determine compliance class. Cannot move account.";
			logger.warn(LOGTAG + msg);
			addResultProperty("message", msg);
			
		}
		else {
			logger.info(LOGTAG + "Attempting to move the account to the pending " +
				"delete org unit.");
			
			// Determine the sourceParentId.
			setSourceParentId(getRootSourceParentId());
			
			// Set the sourceParentId based on compliance class.
			
			if (account.getComplianceClass().equalsIgnoreCase("HIPAA")) {
				logger.info(LOGTAG + "Account is a HIPAA account.");
				setSourceParentId(getHipaaSourceParentId());
			}
			else {
				logger.info(LOGTAG + "Account is a standard account.");
				setSourceParentId(getStandardSourceParentId());
			}
			
			// Move the account to the pending delete organizational unit.
			logger.info(LOGTAG + "Moving the account " + accountId + 
				" from the " + getSourceParentId() + " to the admin org unit "
				+ getDestinationParentId());
			
			// Build the request.
			MoveAccountRequest request = new MoveAccountRequest();
			request.setAccountId(accountId);
			request.setDestinationParentId(getDestinationParentId());
			request.setSourceParentId(getSourceParentId());
			
			// Send the request.
			try {
				logger.info(LOGTAG + "Sending the move account request...");
				long moveStartTime = System.currentTimeMillis();
				MoveAccountResult result = getAwsOrganizationsClient().moveAccount(request);
				long moveTime = System.currentTimeMillis() - moveStartTime;
				logger.info(LOGTAG + "received response to move account request in " +
					moveTime + " ms.");
				accountMoved = true;
			}
			catch (Exception e) {
				String errMsg = "An error occurred moving the account. " +
					"The exception is: " + e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}
			
			addResultProperty("sourceParentId", getSourceParentId());	
			addResultProperty("destinationParentId", getDestinationParentId());
			addResultProperty("movedAccount", Boolean.toString(accountMoved));
			
			if 	(accountMoved) {
				logger.info(LOGTAG + "Successfully moved account " +
					accountId + "to org unit " + getDestinationParentId());
				
			}
			else {
				logger.info(LOGTAG + "Account was not moved.");
			}
		}

		// Update the step result.
		String stepResult = FAILURE_RESULT;
		if (accountMoved == true) {
			stepResult = SUCCESS_RESULT;
		}
		
		// Update the step.
		update(COMPLETED_STATUS, stepResult);
		
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[MoveAccountToPendingDeleteOrg.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
		
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
			"[MoveAccountToPendingDeleteOrg.fail] ";
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
		String LOGTAG = getStepTag() + 
			"[MoveAccountToPendingDeleteOrg.rollback] ";
		logger.info(LOGTAG + "Rollback called, nothing to roll back.");
		
		// Get the result props
		List<Property> props = getResultProperties();
		
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setAwsOrganizationsClient(AWSOrganizationsClient client) {
		m_awsOrganizationsClient = client;
	}
	
	private AWSOrganizationsClient getAwsOrganizationsClient() {
		return m_awsOrganizationsClient;
	}
	
	private void setAccessKey (String accessKey) throws 
		StepException {
	
		if (accessKey == null) {
			String errMsg = "accessKey property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
		
		m_accessKey = accessKey;
	}

	private String getAccessKey() {
		return m_accessKey;
	}
	
	private void setSecretKey (String secretKey) throws 
		StepException {

		if (secretKey == null) {
			String errMsg = "secretKey property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_secretKey = secretKey;
	}

	private String getSecretKey() {
		return m_secretKey;
	}
	
	private void setRootSourceParentId (String id) throws 
		StepException {
	
		if (id == null) {
			String errMsg = "rootSourceParentId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_rootSourceParentId = id;
	}

	private String getRootSourceParentId() {
		return m_rootSourceParentId;
	}
	
	private void setStandardSourceParentId (String id) throws 
		StepException {
	
		if (id == null) {
			String errMsg = "standardSourceParentId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_standardSourceParentId = id;
	}

	private String getStandardSourceParentId() {
		return m_standardSourceParentId;
	}

	private void setHipaaSourceParentId (String id) throws 
		StepException {
	
		if (id == null) {
			String errMsg = "hipaaSourceParentId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_hipaaSourceParentId = id;
	}
	
	private String getHipaaSourceParentId() {
		return m_hipaaSourceParentId;
	}
	
	private void setDestinationParentId (String id) throws 
		StepException {
	
		if (id == null) {
			String errMsg = "destinationParentId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_destinationParentId = id;
	}

	private String getDestinationParentId() {
		return m_destinationParentId;
	}
	
	private void setSourceParentId (String id) throws 
		StepException {
	
		if (id == null) {
			String errMsg = "sourceParentId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_sourceParentId = id;
	}
	
	private String getSourceParentId() {
		return m_sourceParentId;
	}
	
	private Account queryForAccount(String accountId) throws StepException {
		String LOGTAG = "[MoveAccountToPendingDeleteOrg.queryForAccountMetadata] ";
		
		// Query for the account
		// Get a configured account object and account query spec
		// from AppConfig.
		Account account = new Account();
		AccountQuerySpecification querySpec = new AccountQuerySpecification();
	    try {
	    	account = (Account)getAppConfig()
		    	.getObjectByType(account.getClass().getName());
	    	querySpec = (AccountQuerySpecification)getAppConfig()
			    	.getObjectByType(querySpec.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, ecoe);
	    }
	    
	    // Set the values of the query spec
	    try {
	    	querySpec.setAccountId(accountId);
	    }
	    catch (EnterpriseFieldException efe) {
	    	String errMsg = "An error occurred setting a field value. " +
	    		"The exception is: " + efe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException();
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
		    
		// Query for the account metadata
		List results = null;
		try { 
			long queryStartTime = System.currentTimeMillis();
			results = account.query(querySpec, rs);
			long createTime = System.currentTimeMillis() - queryStartTime;
			logger.info(LOGTAG + "Queried for Account in " + createTime +
				" ms. Got " + results.size() + " result(s).");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the object. " +
	    	  "The exception is: " + eoqe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, eoqe);
		}
		finally {
			// Release the producer back to the pool
			getAwsAccountServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		
		Account resultAccount = null;
		if (results.get(0) != null) {
			resultAccount = (Account)results.get(0);
		}
		return resultAccount;
	}
	
	private void setAwsAccountServiceProducerPool(ProducerPool pool) {
		m_awsAccountServiceProducerPool = pool;
	}
	
	private ProducerPool getAwsAccountServiceProducerPool() {
		return m_awsAccountServiceProducerPool;
	}
	
}
