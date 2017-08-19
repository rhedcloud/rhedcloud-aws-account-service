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
     * 
     * <P>
     * 
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
     * @param StackQuerySpecficiation, the query parameter.
     * @return List, a list of matching Stack objects.
     *         <P>
     * @throws ProviderException
     *             with details of the providing the list.
     */
    public List<Property> execute() throws StepException;  

    /**
     * 
     * <P>
     * 
     * @param StackRequisition, the generate parameter.
     * @return Stack, a generated Stack for the requisition.
     *         <P>
     * @throws ProviderException with details of the error generating the stack.
     */
    public void rollback() throws StepException;
    /**
     * 
     * <P>
     * 
     * @param StackRequisition, the generate parameter.
     * @return Stack, a generated Stack for the requisition.
     *         <P>
     * @throws ProviderException with details of the error generating the stack.
     */
    public String getStepId();
    /**
     * 
     * <P>
     * 
     * @param StackRequisition, the generate parameter.
     * @return Stack, a generated Stack for the requisition.
     *         <P>
     * @throws ProviderException with details of the error generating the stack.
     */
    public String getType();
    /**
     * 
     * <P>
     * 
     * @param StackRequisition, the generate parameter.
     * @return Stack, a generated Stack for the requisition.
     *         <P>
     * @throws ProviderException with details of the error generating the stack.
     */
    public String getDescription();
    /**
     * 
     * <P>
     * 
     * @param StackRequisition, the generate parameter.
     * @return Stack, a generated Stack for the requisition.
     *         <P>
     * @throws ProviderException with details of the error generating the stack.
     */
    public String getResult();
    
    public void update(String status, String result, List props) 
    	throws StepException;
    
    
}