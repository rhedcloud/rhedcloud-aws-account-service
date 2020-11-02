/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleProvisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.RoleProvisioningProvider;
import org.openeai.config.AppConfig;

import java.util.List;
import java.util.Properties;


/**
 * Example step that can serve as a placeholder.
 */
public class ExampleStep extends AbstractStep implements Step {
	private int m_sleepTimeInMillis = 5000;

	public void init (String provisioningId, Properties props, AppConfig aConfig, RoleProvisioningProvider rpp) throws StepException {
		super.init(provisioningId, props, aConfig, rpp);
		
		String LOGTAG = getStepTag() + "[RoleProvisioning.ExampleStep.init] ";
		
		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");
		
		String sleepTime = getProperties().getProperty("sleepTimeInMillis", "5000");
		
		int sleepTimeInMillis = Integer.parseInt(sleepTime);
		setSleepTimeInMillis(sleepTimeInMillis);
		logger.info(LOGTAG + "sleepTimeInMillis is: " + getSleepTimeInMillis());
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[RoleProvisioning.ExampleStep.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		// Wait some time.
		logger.info(LOGTAG + "Sleeping for " + getSleepTimeInMillis() + " ms.");
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
		addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);
		addResultProperty("sleepTimeInMillis", Integer.toString(getSleepTimeInMillis()));
		
		// Update the step.
    	update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[RoleProvisioning.ExampleStep.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_SIMULATED);
		
		// Update the step.
    	update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[RoleProvisioning.ExampleStep.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
    	addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_FAILURE);
		
		// Update the step.
    	update(STEP_STATUS_COMPLETED, STEP_RESULT_FAILURE);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");
    	
    	// Return the properties.
		return getResultProperties();
	}
	
	public void rollback() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[RoleProvisioning.ExampleStep.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

		// Update the step.
		update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
		logger.info(LOGTAG + "Step rollback completed in " + time + "ms.");
	}
	
	private void setSleepTimeInMillis(int time) { m_sleepTimeInMillis = time; }
	private int getSleepTimeInMillis() { return m_sleepTimeInMillis; }
}
