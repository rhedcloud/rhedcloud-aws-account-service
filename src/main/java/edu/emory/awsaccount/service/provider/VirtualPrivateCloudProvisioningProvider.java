/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

// Core Java
import java.util.List;

// OpenEAI config foundation
import org.openeai.config.AppConfig;

// AWS Message Object API (MOA)
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudProvisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;


/**
 * Interface for all VirtualPrivateCloudProvisioning object providers.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 6 June 2016
 */
public interface VirtualPrivateCloudProvisioningProvider {
    /**
     * 
     * <P>
     * 
     * @param AppConfig
     *            , an AppConfig object with all this provider needs.
     *            <P>
     * @throws ProviderException
     *             with details of the initialization error.
     */
    public void init(AppConfig aConfig) throws ProviderException;

    /**
     * 
     * <P>
     * 
     * @param VirtualPrivateCloudProvisioningQuerySpecficiation, the query parameter.
     * @return List, a list of matching VirtualPrivateCloudProvisioning objects.
     *         <P>
     * @throws ProviderException
     *             with details of the providing the list.
     */
    public List<VirtualPrivateCloudProvisioning> query(VirtualPrivateCloudProvisioningQuerySpecification querySpec) 
    	throws ProviderException;

    /**
     * 
     * <P>
     * 
     * @param VirtualPrivateCloudRequisition, the generate parameter.
     * @return VirtualPrivateCloudProvisioning, a generated VPC for the requisition.
     *         <P>
     * @throws ProviderException with details of the error generating the VPCP.
     */
    public VirtualPrivateCloudProvisioning generate(VirtualPrivateCloudRequisition requisition) 
    	throws ProviderException;

    /**
     * 
     * <P>
     * 
     * @param VirtualPrivateCloudProvisioning, the new VPC to create.
     *            <P>
     * @throws ProviderException with details of the error creating the VPCP.
     */
    public void create(VirtualPrivateCloudProvisioning vpcp) throws ProviderException;
    
    
    /**
     * 
     * <P>
     * 
     * @param VirtualPrivateCloudProvisioning, the new state of the VPC to update.
     *            <P>
     * @throws ProviderException with details of the error deleting the VPCP.
     */
    public void update(VirtualPrivateCloudProvisioning vpcp) throws ProviderException;
    
    /**
     * 
     * <P>
     * 
     * @param VirtualPrivateCloud, the object to delete.
     *            <P>
     * @throws ProviderException with details of the error deleting the VPCP.
     */
    public void delete(VirtualPrivateCloudProvisioning vpcp) throws ProviderException;

}