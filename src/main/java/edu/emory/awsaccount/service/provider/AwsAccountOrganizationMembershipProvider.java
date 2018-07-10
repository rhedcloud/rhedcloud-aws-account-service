/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.util.List;

import org.apache.log4j.Logger;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.jms.consumer.commands.provider.AbstractCrudProvider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountOrganizationMembership;
import com.amazon.aws.moa.objects.resources.v1_0.AccountOrganizationMembershipQuerySpecification;
import com.amazonaws.services.organizations.AWSOrganizations;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.CreateOrganizationalUnitRequest;

public class AwsAccountOrganizationMembershipProvider
        extends AbstractCrudProvider<AccountOrganizationMembership, AccountOrganizationMembershipQuerySpecification> {

    private static Logger logger = Logger.getLogger(AwsAccountOrganizationMembershipProvider.class);
    private String LOGTAG = "[AwsAccountOrganizationMembershipProvider] ";
    private AWSOrganizations organizations = AWSOrganizationsClientBuilder.defaultClient();

    @Override
    public List<AccountOrganizationMembership> query(AccountOrganizationMembershipQuerySpecification querySpec)
            throws org.openeai.jms.consumer.commands.provider.ProviderException {

        // If the StackId is null, throw an exception.
        if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
            String errMsg = "The StackId is null. The ExampleStackProvider" + "presently only implements query by StackId.";
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg);
        }

        // ListAccountOrganizationMembershipesRequest request = new
        // ListAccountOrganizationMembershipesRequest();
        // // TODO: querySpec to request
        // ListAccountOrganizationMembershipesResult result =
        // amazonIdentityManagement.listAccountOrganizationMembershipes(request);
        // Replace the object in the map with the same StackId.
        // TODO: check result

        return null;
    }

    /**
     * @see StackProvider.java
     */
    @Override
    public void create(AccountOrganizationMembership req) throws org.openeai.jms.consumer.commands.provider.ProviderException {

        // Get a configured Stack object from AppConfig
        AccountOrganizationMembership samlProvider = new AccountOrganizationMembership();
        try {
            samlProvider = (AccountOrganizationMembership) appConfig.getObjectByType(samlProvider.getClass().getName());
            // TODO: map req to request
            CreateOrganizationalUnitRequest request = new CreateOrganizationalUnitRequest();
            request.setParentId(req.getParentId());
            // request.
            // CreateAccountResult result =
            // organizations.createOrganizationalUnit(request);
            // TODO: check result
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg, ecoe);
        }

        // Add the Stack to the map.
        // m_stackMap.put(req.getAccountId(), req);
    }

    /**
     * @see StackProvider.java
     */
    @Override
    public void delete(AccountOrganizationMembership stack) throws org.openeai.jms.consumer.commands.provider.ProviderException {

        // DeleteAccountOrganizationMembershipRequest request = new
        // DeleteAccountOrganizationMembershipRequest();
        // // TODO: samlProvier to request
        // DeleteAccountOrganizationMembershipResult result =
        // amazonIdentityManagement.deleteAccountOrganizationMembership(request);
        // Replace the object in the map with the same StackId.
        // TODO: check result));

        return;
    }

}
