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
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.moa.objects.resources.Result;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountProvisioningAuthorization;
import com.amazon.aws.moa.objects.resources.v1_0.AccountProvisioningAuthorizationQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.EmailAddress;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * If this is a new account request, create and account alias.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 19 December 2018
 **/
public class CreateAccountAlias extends AbstractStep implements Step {
	
	private ProducerPool m_awsAccountServiceProducerPool = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[CreateAccountAlias.init] ";
		
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
		String LOGTAG = getStepTag() + "[CreateAccountAlias.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean accountAliasCreated = false;
		
		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		
		// Get some properties from previous steps.
		String allocateNewAccount = 
			getStepPropertyValue("GENERATE_NEW_ACCOUNT", "allocateNewAccount");
		String newAccountId = 
			getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");
		String name = 
			getStepPropertyValue("VERIFY_REMAINING_DISTRO_LISTS", "accountAlias");
		
		boolean allocatedNewAccount = Boolean.getBoolean(allocateNewAccount);
		logger.info(LOGTAG + "allocatedNewAccount: " + allocatedNewAccount);
		logger.info(LOGTAG + "newAccountId: " + newAccountId);
		
		// If allocatedNewAccount is true and newAccountId is not null, 
		// Send an AccountAlias.Create-Request to the AWS Account service.
		if (allocatedNewAccount && (newAccountId != null && newAccountId.equalsIgnoreCase("null") == false)) {
			logger.info(LOGTAG + "allocatedNewAccount is true and newAccountId " + 
				"is not null. Sending an AccountAlias.Create-Request to create an" +
				"acount alias.");
			
			// Get a configured account object from AppConfig.
			AccountAlias accountAlias = new AccountAlias();
		    try {
		    	accountAlias = (AccountAlias)getAppConfig()
			    	.getObjectByType(accountAlias.getClass().getName());
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
		    	accountAlias.setAccountId(newAccountId);
		    	accountAlias.setName(name);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "AccountAlias. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the AccountAlias.
		    try {
		    	logger.info(LOGTAG + "AccountAlias to create is: " +
		    		accountAlias.toXmlString());
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
				accountAlias.create(rs);
				long createTime = System.currentTimeMillis() - createStartTime;
				logger.info(LOGTAG + "Create Account in " + createTime +
					" ms.");
				accountAliasCreated = true;
				addResultProperty("allocatedNewAccount", 
					Boolean.toString(allocatedNewAccount));
				addResultProperty("accountAliasCreated", 
					Boolean.toString(accountAliasCreated));
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
			addResultProperty("allocatedNewAccount", 
				Boolean.toString(allocatedNewAccount));
			addResultProperty("accountAliasCreated", 
				"not applicable");
		}
		
		// Update the step result.
		String stepResult = FAILURE_RESULT;
		if (accountAliasCreated == true && allocatedNewAccount == true) {
			stepResult = SUCCESS_RESULT;
		}
		if (allocatedNewAccount == false) {
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
			"[CreateAccountAlias.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
    	addResultProperty("accountAliasCreated", "true");
    	
		
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
			"[CreateAccountAlias.fail] ";
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
		String LOGTAG = getStepTag() + 
				"[CreateAccountAlias.rollback] ";
		long startTime = System.currentTimeMillis();
		
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
}
