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
import org.openeai.jms.consumer.commands.provider.CrudProvider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;

/**
 * Interface for all AccountAlias object providers.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 12 July 2018
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
     * @param AccountAliasQuerySpecficiation,
     *            the query parameter.
     * @return List, a list of matching AccountAlias objects.
     *         <P>
     * @throws ProviderException
     *             with details of the providing the list.
     */
    public List<AccountAlias> query(AccountAliasQuerySpecification querySpec) throws ProviderException;

    /**
     * 
     * <P>
     * 
     * @param AccountAlias,
     *            the object to create.
     *            <P>
     * @throws ProviderException
     *             with details of the error generating the object.
     */
    public void create(AccountAlias alias) throws ProviderException;

    /**
     * 
     * <P>
     * 
     * @param Stack,
     *            the object to delete.
     *            <P>
     * @throws ProviderException
     *             with details of the error deleting the stack.
     */
    public void delete(AccountAlias alias) throws ProviderException;

}