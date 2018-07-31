/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import org.openeai.jms.consumer.commands.provider.CrudProvider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;

/**
 * Interface for all AccountAlias object providers.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 12 July 2018
 */
public interface GenericAccountAliasProvider extends CrudProvider<AccountAlias, AccountAliasQuerySpecification> {
}