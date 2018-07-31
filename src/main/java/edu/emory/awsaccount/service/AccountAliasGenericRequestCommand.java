/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service;

// OpenEAI foundation components
import org.openeai.config.CommandConfig;
import org.openeai.jms.consumer.commands.GenericCrudRequestCommand;
import org.openeai.jms.consumer.commands.RequestCommand;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;

import edu.emory.awsaccount.service.provider.AccountAliasProvider;
import edu.emory.awsaccount.service.provider.GenericAccountAliasProvider;

/**
 * This command handles requests for AccountAlias objects. Specifically, it
 * handles a Query-Request, a Create-Request,
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 12 July 2018
 * 
 */

public class AccountAliasGenericRequestCommand extends
        GenericCrudRequestCommand<AccountAlias, AccountAliasQuerySpecification, GenericAccountAliasProvider> implements RequestCommand {
    public AccountAliasGenericRequestCommand(CommandConfig cConfig) throws InstantiationException {
        super(cConfig);
    }
}
