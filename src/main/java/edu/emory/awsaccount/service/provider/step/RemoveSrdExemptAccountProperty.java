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
 * Example step that can serve as a placholder.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 **/
public class RemoveSrdExemptAccountProperty extends AbstractStep implements Step {
	
	int m_sleepTimeInMillis = 5000;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[RemoveSrdExemptAccountProperty.init] ";
		
		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");
		
		String sleepTime = getProperties()
			.getProperty("sleepTimeInMillis", "5000");
		
		int sleepTimeInMillis = Integer.parseInt(sleepTime);
		setSleepTimeInMillis(sleepTimeInMillis);
		logger.info(LOGTAG + "sleepTimeInMillis is: " + 
			getSleepTimeInMillis());
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[RemoveSrdExemptAccountProperty.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		logger.info(LOGTAG + "Sleeping for " + getSleepTimeInMillis()
			+ " ms.");
		
		// Wait some time.
		try {
			Thread.sleep(getSleepTimeInMillis());
		}
		catch (InterruptedException ie) {
			String errMsg = "Error occurred sleeping.";
			logger.error(LOGTAG + errMsg + ie.getMessage());
			throw new StepException(errMsg, ie);
		}
		
		logger.info(LOGTAG + "Done sleeping.");
		
		// Set return properties.
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		addResultProperty("sleepTimeInMillis", 
			Integer.toString(getSleepTimeInMillis()));
		
		// Update the step.
    	update(COMPLETED_STATUS, SUCCESS_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[RemoveSrdExemptAccountProperty.simulate] ";
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
			"[RemoveSrdExemptAccountProperty.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
    	addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
	}
	
	public void rollback() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[RemoveSrdExemptAccountProperty.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setSleepTimeInMillis(int time) {
		m_sleepTimeInMillis = time;
	}
	
	private int getSleepTimeInMillis() {
		return m_sleepTimeInMillis;
	}
	
}
