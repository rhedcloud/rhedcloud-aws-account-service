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

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.SamlProvider;
import com.amazon.aws.moa.objects.resources.v1_0.SamlProviderQuerySpecification;

import edu.emory.awsaccount.service.provider.SamlProviderProvider;

public class SamlProviderRequestCommand
        extends GenericCrudRequestCommand<SamlProvider, SamlProviderQuerySpecification, SamlProviderProvider> implements RequestCommand {
    public SamlProviderRequestCommand(CommandConfig cConfig) throws InstantiationException {
        super(cConfig);

    }
}
