/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import org.openeai.jms.consumer.commands.provider.ExampleCrudProvider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountOrganizationMembership;
import com.amazon.aws.moa.objects.resources.v1_0.AccountOrganizationMembershipQuerySpecification;

public class ExampleAccountOrganizationMembershipProvider
        extends ExampleCrudProvider<AccountOrganizationMembership, AccountOrganizationMembershipQuerySpecification> {
}
