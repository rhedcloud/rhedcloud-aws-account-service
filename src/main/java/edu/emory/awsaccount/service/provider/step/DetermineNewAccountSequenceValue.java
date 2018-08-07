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
import org.openeai.utils.sequence.Sequence;
import org.openeai.utils.sequence.SequenceException;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * If a new account is needed, increment the account sequence to get the 
 * Emory serial number of the new AWS account.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 5 August 2018
 **/
public class DetermineNewAccountSequenceValue extends AbstractStep implements Step {

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[DetermineNewAccountSequence.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		String accountSequenceNumber = null;
		
		// Get the allocateNewAccount property from the
		// DETERMINE_NEW_OR_EXISTING_ACCOUNT step.
		ProvisioningStep step = getProvisioningStep("DETERMINE_NEW_OR_EXISTING_ACCOUNT");
		String sAllocateNewAccount = getResultProperty(step, "allocateNewAccount");
		boolean allocateNewAccount = Boolean.parseBoolean(sAllocateNewAccount);
		
		// If allocateNewAccount is true, increment the sequence number and
		// set the accountSequenceNumber property.
		if (allocateNewAccount) {
			// Get the AccountSequence object from AppConfig
			Sequence accountSeq = null;
			try {
				accountSeq = (Sequence)getAppConfig().getObject("AccountSequence");
			}
			catch (EnterpriseConfigurationObjectException ecoe) {
				// An error occurred retrieving an object from AppConfig. Log it and
				// throw an exception.
				String errMsg = "An error occurred retrieving an object from " +
						"AppConfig. The exception is: " + ecoe.getMessage();
				logger.fatal(LOGTAG + errMsg);
				throw new StepException(errMsg, ecoe);
			}
			
			// Increment the sequence value
			try {
				accountSequenceNumber = accountSeq.next();
			}
			catch (SequenceException se) {
				String errMsg = "An error occurred incrementing the " +
					"AccountSequence. The exception is: " + se.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, se);
			}
		}
		// If allocateNewAccount is false, log it.
		else {
			logger.info(LOGTAG + "allocateNewAccount is false. " +
				"The account sequence was not incremented.");
		}
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
		props.add(buildProperty("stepExecutionMethod", RUN_EXEC_TYPE));
		if (accountSequenceNumber != null) {
			props.add(buildProperty("accountSequenceNumber", accountSequenceNumber));
		}
		
		// Update the step.
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
			"[DetermineNewAccountSequence.simulate] ";
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
			"[DetermineNewAccountSequence.fail] ";
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
			"[DetermineNewAccountSequence.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT, null);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
}
