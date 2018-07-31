/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

// Core Java
import java.util.List;

// OpenEAI config foundation
import org.openeai.config.AppConfig;

import com.amazon.aws.moa.jmsobjects.user.v1_0.AccountUser;
import com.amazon.aws.moa.objects.resources.v1_0.AccountUserQuerySpecification;


/**
 * Interface for all AccountUser object providers.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 22 July 2018
 */
public interface AccountUserProvider {
    /**
     * 
     * <P>
     * 
     * @param AppConfig, an AppConfig object with all this provider needs.
     * <P>
     * @throws ProviderException with details of the initialization error.
     */
    public void init(AppConfig aConfig) throws ProviderException;

    /**
     *
     * @param AccountUserQuerySpecficiation, the query parameter.
     * @return List, a list of matching AccountUser objects.
     * <P>
     * @throws ProviderException with details of the providing the list.
     */
    public List<AccountUser> query(AccountUserQuerySpecification querySpec) 
    	throws ProviderException;  

}