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

import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountProvisioningAuthorization;
import com.amazon.aws.moa.objects.resources.v1_0.AccountProvisioningAuthorizationQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * If this is a new account request, send an 
 * AccountProvisioningAuthorization to determine if the user 
 * is authorized to create a new account.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 5 August 2018
 **/
public class AuthorizeNewAccountOwner extends AbstractStep implements Step {

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[AuthorizeNewAccountRequestor.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean isAuthorized = false;
		
		// Return properties
		List<Property> props = new ArrayList<Property>();
		props.add(buildProperty("stepExecutionMethod", RUN_EXEC_TYPE));
		
		// Get the allocateNewAccount property from the
		// DETERMINE_NEW_OR_EXISTING_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step = getProvisioningStepByType("DETERMINE_NEW_OR_EXISTING_ACCOUNT");
		String sAllocateNewAccount = getResultProperty(step, "allocateNewAccount");
		boolean allocateNewAccount = Boolean.parseBoolean(sAllocateNewAccount);
		props.add(buildProperty("allocateNewAccount", Boolean.toString(allocateNewAccount)));
		
		// If allocateNewAccount is true, send an AccountProvisioningAuthorization.Query-Request
		// to the AWS Account Service
		if (allocateNewAccount) {
			logger.info(LOGTAG + "allocateNewAccount is true. " + 
				"Sending an AccountProvisioningAuthorization.Query-Request " +
				"to determine if the user is authorized to provisiong a new " +
				"account.");
			
			// Query for the AccountProvisioningAuthorization object 
			// in the AWS Account Service. Get a configured object and query spec
			// from AppConfig.
			AccountProvisioningAuthorization apa = new
					AccountProvisioningAuthorization();
			AccountProvisioningAuthorizationQuerySpecification apaqs = new
					AccountProvisioningAuthorizationQuerySpecification();
		    try {
		    	apa = (AccountProvisioningAuthorization)getAppConfig()
			    		.getObjectByType(apa.getClass().getName());
		    	apaqs = (AccountProvisioningAuthorizationQuerySpecification)getAppConfig()
			    		.getObjectByType(apaqs.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
			
		    // Get the UserId of the account owner.
		    String ownerUserId = getVirtualPrivateCloudProvisioning()
		    	.getVirtualPrivateCloudRequisition().getAccountOwnerUserId();
		    props.add(buildProperty("ownerUserId", ownerUserId));
		    
		    // Set the values of the query spec.
		    try {
		    	apaqs.setUserId(ownerUserId);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "VPCP query spec. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the query spec.
		    try {
		    	logger.info(LOGTAG + "Query spec is: " + apaqs.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the query spec " +
		  	    	  "to XML. The exception is: " + xeoe.getMessage();
	  	    	logger.error(LOGTAG + errMsg);
	  	    	throw new StepException(errMsg, xeoe);
		    }
		    
		    // Get a request service to use.
			RequestService rs = null;
			try {
				rs = (RequestService)getAppConfig()
					.getObject("AwsAccountServiceProducerPool");
			}
			catch (EnterpriseConfigurationObjectException ecoe) {
				// An error occurred retrieving an object from AppConfig. Log it and
				// throw an exception.
				String errMsg = "An error occurred retrieving an object from " +
						"AppConfig. The exception is: " + ecoe.getMessage();
				logger.fatal(LOGTAG + errMsg);
				throw new StepException(errMsg, ecoe);
			}
		    
			List results = null;
			try { 
				results = apa.query(apaqs, rs);
			}
			catch (EnterpriseObjectQueryException eoqe) {
				String errMsg = "An error occurred querying for the  " +
		    	  "AccountProvisioningAuthorization object. " +
		    	  "The exception is: " + eoqe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoqe);
			}
			
			if (results.size() == 1) {
				AccountProvisioningAuthorization apaResult = 
						(AccountProvisioningAuthorization)results.get(0);
				String sIsAuthorized = apaResult.getIsAuthorized();
				if (sIsAuthorized.equalsIgnoreCase("true")) {
					isAuthorized = true;
					logger.info(LOGTAG + "isAuthorized is true");
					props.add(buildProperty("isAuthorized", Boolean.toString(isAuthorized)));
				}
				else {
					logger.info(LOGTAG + "isAuthorized is false");
					props.add(buildProperty("isAuthorized", Boolean.toString(isAuthorized)));
				}
			}
			else {
				String errMsg = "Invalid number of results returned from " +
					"AccountProvisioningAuthorization.Query-Request. " +
					results.size() + " results returned. Expected exactly 1.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
			
		}
		// If allocateNewAccount is false, log it and add result props.
		else {
			logger.info(LOGTAG + "allocateNewAccount is false. " +
				"no need to authorize the user to create a new account.");
			props.add(buildProperty("allocateNewAccount", Boolean.toString(allocateNewAccount)));
			props.add(buildProperty("isAuthorized", "not applicable"));
		}
		
		// Update the step.
		if (allocateNewAccount == false || isAuthorized == true) {
			update(COMPLETED_STATUS, SUCCESS_RESULT, props);
		}
		else update(COMPLETED_STATUS, FAILURE_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[AuthorizeNewAccountRequestor.simulate] ";
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
			"[AuthorizeNewAccountRequestor.fail] ";
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
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[AuthorizeNewAccountRequestor.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT, null);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
}
