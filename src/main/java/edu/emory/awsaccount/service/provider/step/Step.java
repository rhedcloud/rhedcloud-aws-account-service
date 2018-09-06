/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider.step;

// Core Java
import java.util.List;
import java.util.Properties;

// OpenEAI Core
import org.openeai.config.AppConfig;

// AWS MOA 
import com.amazon.aws.moa.objects.resources.v1_0.Property;

// Step foundation
import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * Interface for all provisioning steps.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 */
public interface Step {
	/**
     * @param AppConfig, an AppConfig object with all this step needs.
     *            <P>
     * @throws StepException with details of the initialization error.
     */
    public void init(String provisioningId, Properties props, 
			AppConfig aConfig, 
			VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException;   
    /**
     * 
     * <P>
     * 
     * @return List, a list of execution result properties.
     *         <P>
     * @throws StepException, with details of the error executing the step.
     */
    public List<Property> execute() throws StepException;  
    /**
     * 
     * <P>
     *
     * @throws StepException, with details of the error rolling back the step.
     */
    public void rollback() throws StepException;
    /**
     * @return String, the step ID.
     */
    public String getStepId();
    /**
     * 
     * @return String, the step type
     */
    public String getType();
    /**
     * @return String, the step description
     */
    public String getDescription();
    /**
     * @return String, the step result
     */
    public String getResult();
    /**
     * @return List<Property>, the step result properties
     */
    public List<Property> getResultProperties();
    /**
     * @param String, step status
     * @param String, step result
     * @param List, step properties
     * <P>
     * @return String, the step result
     * @throws StepException 
     */
    public void update(String status, String result, List props) 
    	throws StepException;
}