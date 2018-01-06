/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

// Core Java
import java.util.List;

// OpenEAI config foundation
import org.openeai.config.AppConfig;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;
//import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;


/**
 * Interface for all CloudFormation Stack object providers.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 25 December 2016
 */
public interface AccountAliasProvider {
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
     * @param StackQuerySpecficiation, the query parameter.
     * @return List, a list of matching Stack objects.
     *         <P>
     * @throws ProviderException
     *             with details of the providing the list.
     */
    public List<AccountAlias> query(AccountAliasQuerySpecification querySpec) 
    	throws ProviderException;  

    /**
     * 
     * <P>
     * 
     * @param StackRequisition, the generate parameter.
     * @return Stack, a generated Stack for the requisition.
     *         <P>
     * @throws ProviderException with details of the error generating the stack.
     */
    public void create(AccountAlias requisition) 
    	throws ProviderException;
    
    
    /**
     * 
     * <P>
     * 
     * @param Stack, the object to delete.
     *            <P>
     * @throws ProviderException with details of the error deleting the stack.
     */
    public void delete(AccountAlias stack) throws ProviderException;

}