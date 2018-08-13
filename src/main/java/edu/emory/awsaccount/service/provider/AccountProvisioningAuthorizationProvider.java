/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.util.List;

import org.openeai.config.AppConfig;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountProvisioningAuthorization;
import com.amazon.aws.moa.objects.resources.v1_0.AccountProvisioningAuthorizationQuerySpecification;

/**
 * Interface for all AccountAlias object providers.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 13 August 2018
 */

public interface AccountProvisioningAuthorizationProvider {
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
     * @param AccountAliasQuerySpecficiation,
     *            the query parameter.
     * @return List, a list of matching AccountAlias objects.
     *         <P>
     * @throws ProviderException
     *             with details of the providing the list.
     */
    public List<AccountProvisioningAuthorization> query(AccountProvisioningAuthorizationQuerySpecification querySpec) throws ProviderException;

}