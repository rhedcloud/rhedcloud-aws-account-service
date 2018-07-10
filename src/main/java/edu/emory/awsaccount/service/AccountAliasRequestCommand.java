/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service;

// OpenEAI foundation components
import org.openeai.config.CommandConfig;
import org.openeai.jms.consumer.commands.GenericCrudRequestCommand;
import org.openeai.jms.consumer.commands.RequestCommand;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;

import edu.emory.awsaccount.service.provider.AwsAccountAliasProvider;

public class AccountAliasRequestCommand
        extends GenericCrudRequestCommand<AccountAlias, AccountAliasQuerySpecification, AwsAccountAliasProvider> implements RequestCommand {
    public AccountAliasRequestCommand(CommandConfig cConfig) throws InstantiationException {
        super(cConfig);
    }
}
