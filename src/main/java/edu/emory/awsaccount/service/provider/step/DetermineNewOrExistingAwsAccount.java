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

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * Step to determine if a new account should be created or if an
 * existing account should be used.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 **/
public class DetermineNewOrExistingAwsAccount extends AbstractStep implements Step {

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[DetermineNewOrExistingAccount.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		// By default, allocateNewAccount is false.
		boolean allocateNewAccount = false;
		
		// Get the requisition.
		VirtualPrivateCloudRequisition vpcr = 
			getVirtualPrivateCloudProvisioning()
			.getVirtualPrivateCloudRequisition();
		
		if (vpcr != null) {
			logger.info(LOGTAG + "The VirtualPrivateCloudRequisition " +
				"is not null.");
			String accountId = vpcr.getAccountId();
			if (accountId != null) {
				logger.info(LOGTAG + "The AccountId in the requisition is: "
					+ vpcr.getAccountId());
			}
			else {
				logger.info(LOGTAG + "The AccountId in the requisition is null.");
			}
		}
		
		// If there is no AWS account ID specified, a new account is 
		// needed.
		if (vpcr.getAccountId() == null) {
			allocateNewAccount = true;
		}
		
		logger.info(LOGTAG + "allocateNetAccount is: " + allocateNewAccount);
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
		props.add(buildProperty("stepExecutionMethod", RUN_EXEC_TYPE));
		props.add(buildProperty("allocateNewAccount", 
			Boolean.toString(allocateNewAccount)));
		
	
		logger.info(LOGTAG + "Set return props.");
		
		// Update the step.
		logger.info(LOGTAG + "Performing update...");
    	update(COMPLETED_STATUS, SUCCESS_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[DetermineNewOrExistingAccount.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
		Property prop = buildProperty("allocateNewAccount", "true");
    	props.add(buildProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE));
		
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
			"[DetermineNewOrExistingAccount.fail] ";
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
			"[DetermineNewOrExistingAccount.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT, null);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
}
