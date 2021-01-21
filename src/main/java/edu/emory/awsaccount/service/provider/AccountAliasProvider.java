/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;
import org.openeai.config.AppConfig;

import java.util.List;

/**
 * Interface for all AccountAlias object providers.
 * <p>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 12 July 2018
 */

public interface AccountAliasProvider {
    /**
     * <p>
     *
     * @param AppConfig , an AppConfig object with all this provider needs.
     *                  <p>
     * @throws ProviderException with details of the initialization error.
     */
    void init(AppConfig aConfig) throws ProviderException;

    /**
     * <p>
     *
     * @param AccountAliasQuerySpecficiation, the query parameter.
     * @return List, a list of matching AccountAlias objects.
     * <p>
     * @throws ProviderException with details of the providing the list.
     */
    List<AccountAlias> query(AccountAliasQuerySpecification querySpec) throws ProviderException;

    /**
     * <p>
     *
     * @param AccountAlias, the object to create.
     *                      <p>
     * @throws ProviderException with details of the error generating the object.
     */
    void create(AccountAlias alias) throws ProviderException;

    /**
     * <p>
     *
     * @param Stack, the object to delete.
     *               <p>
     * @throws ProviderException with details of the error deleting the stack.
     */
    void delete(AccountAlias alias) throws ProviderException;

}