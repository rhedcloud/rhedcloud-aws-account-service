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
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.validation.v1_0.EmailAddressValidation;
import edu.emory.moa.objects.resources.v1_0.EmailAddressValidationQuerySpecification;

/**
 * If this is an existing account request, send an Account query request
 * to get the compliance class of the account and then determine if the
 * requested VPC compliance class is allowed in this account
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 22 February 2019
 **/
public class VerifyVpcTypeForExistingAccount extends AbstractStep implements Step {
	
	private ProducerPool m_awsAccountServiceProducerPool = null;
	private int m_requestTimeoutInterval = 10000;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[VerifyVpcTypeForExistingAccount.init] ";
	
		// This step needs to send messages to the AwsAccountService to
		// retrieve account information.
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
		
		// requestTimeoutInterval is the time to wait for the
		// response to the request
		String timeout = getProperties().getProperty("requestTimeoutInterval",
			"10000");
		int requestTimeoutInterval = Integer.parseInt(timeout);
		setRequestTimeoutInterval(requestTimeoutInterval);
		logger.info(LOGTAG + "requestTimeoutInterval is: " + 
			getRequestTimeoutInterval());
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[VerifyVpcTypeForExistingAccount.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean isValid = false;
		
		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		
		// Get the allocateNewAccount property from the
		// DETERMINE_NEW_OR_EXISTING_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		String sAllocateNewAccount = 
			getStepPropertyValue("DETERMINE_NEW_OR_EXISTING_ACCOUNT", "allocateNewACcount");
		boolean allocateNewAccount = Boolean.parseBoolean(sAllocateNewAccount);
				
		// If allocateNewAccount is perform the evaluation.
		if (allocateNewAccount == false) {
			logger.info(LOGTAG + "allocateNewAccount is false. Sending an " +
				"Account.Query-Request to get the account metadata to  " +
				"determined of the requested VPC type is valid for this account.");
			
			// Get a configured Account object and query spec from AppConfig.
			Account account = new Account();
			AccountQuerySpecification querySpec = new AccountQuerySpecification();
		    try {
		    	account = (Account)getAppConfig().getObjectByType(account.getClass().getName());
		    	querySpec = (AccountQuerySpecification)getAppConfig()
			    		.getObjectByType(querySpec.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
		    
		    // Build the querySpec.
		    String accountId = 
				getStepPropertyValue("DETERMINE_NEW_OR_EXISTING_ACCOUNT", "accountId");
 			logger.info(LOGTAG + "accountId is: " + accountId);
 			addResultProperty("accountId", accountId);
		    
		    // Set the values of the query spec.
		    try {
		    	querySpec.setAccountId(accountId);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "query spec. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the query spec.
		    try {
		    	logger.info(LOGTAG + "Query spec is: " + querySpec.toXmlString());
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
				PointToPointProducer p2p = 
					(PointToPointProducer)getAwsAccountServiceProducerPool()
					.getExclusiveProducer();
				p2p.setRequestTimeoutInterval(getRequestTimeoutInterval());
				rs = (RequestService)p2p;
			}
			catch (JMSException jmse) {
				String errMsg = "An error occurred getting a producer " +
					"from the pool. The exception is: " + jmse.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, jmse);
			}
		    
			List results = null;
			try { 
				long queryStartTime = System.currentTimeMillis();
				results = account.query(querySpec, rs);
				long queryTime = System.currentTimeMillis() - queryStartTime;
				logger.info(LOGTAG + "Queried for Account for AccountId " +
					accountId + " in " + queryTime + " ms. Returned " + results.size() + 
					" result(s).");
			}
			catch (EnterpriseObjectQueryException eoqe) {
				String errMsg = "An error occurred querying for the  " +
		    	  "Account object. The exception is: " + eoqe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoqe);
			}
			finally {
				// Release the producer back to the pool
				getAwsAccountServiceProducerPool().releaseProducer((MessageProducer)rs);
			}
			
			if (results.size() == 1) {
				Account accountResult = (Account)results.get(0);
				String complianceClass = accountResult.getComplianceClass();
			    VirtualPrivateCloudProvisioning vpcp = getVirtualPrivateCloudProvisioning();
				if (complianceClass.equalsIgnoreCase(vpcp.getVirtualPrivateCloudRequisition().getComplianceClass())) {
					isValid = true;
					logger.info(LOGTAG + "isValid is true");
					addResultProperty("isValid", Boolean.toString(isValid));
				}
				else {
					logger.info(LOGTAG + "isValid is false");
					addResultProperty("isValid", Boolean.toString(isValid));
				}
			}
			else {
				String errMsg = "Invalid number of results returned from " +
					"Account.Query-Request. " + results.size() + 
					" results returned. Expected exactly 1.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
			
		}
		// If allocateNewAccount is true, no evaluation is necessary.
		else {
			logger.info(LOGTAG + "allocateNewAccount is true. " +
				"no need to verify VPC type.");
			addResultProperty("allocateNewAccount", 
				Boolean.toString(allocateNewAccount));
			addResultProperty("isValid", "not applicable");
		}
		
		// Update the step.
		String stepResult = FAILURE_RESULT;
		if (allocateNewAccount == false && isValid == true) {
			stepResult = SUCCESS_RESULT;
		}
		if (allocateNewAccount == true) {
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
			"[VerifyVpcTypeForExistingAccount.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
    	
    	String isValid = "true";
		logger.info(LOGTAG + "isValid is: " + isValid);
		addResultProperty("isValid", isValid);
		
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
			"[VerifyVpcTypeForExistingAccount.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in "
    		+ time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	public void rollback() throws StepException {
		
		super.rollback();
		
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[VerifyVpcTypeForExistingAccount.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
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
	
	private void setRequestTimeoutInterval(int i) {
		m_requestTimeoutInterval = i;
	}
	
	private int getRequestTimeoutInterval() {
		return m_requestTimeoutInterval;
	}
}
