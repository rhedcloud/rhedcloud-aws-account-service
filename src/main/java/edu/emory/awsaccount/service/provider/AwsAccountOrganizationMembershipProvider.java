/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.consumer.commands.provider.AbstractCrudProvider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountOrganizationMembership;
import com.amazon.aws.moa.objects.resources.v1_0.AccountOrganizationMembershipQuerySpecification;
import com.amazonaws.services.organizations.AWSOrganizations;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.Child;
import com.amazonaws.services.organizations.model.CreateOrganizationRequest;
import com.amazonaws.services.organizations.model.CreateOrganizationalUnitRequest;
import com.amazonaws.services.organizations.model.CreateOrganizationalUnitResult;
import com.amazonaws.services.organizations.model.ListChildrenRequest;
import com.amazonaws.services.organizations.model.ListChildrenResult;
import com.amazonaws.services.organizations.model.ListParentsRequest;
import com.amazonaws.services.organizations.model.ListParentsResult;
import com.amazonaws.services.organizations.model.OrganizationalUnit;
import com.amazonaws.services.organizations.model.Parent;

public class AwsAccountOrganizationMembershipProvider
        extends AbstractAwsCrudProvider<AccountOrganizationMembership, AccountOrganizationMembershipQuerySpecification> {

    private static Logger logger = Logger.getLogger(AwsAccountOrganizationMembershipProvider.class);
    private String LOGTAG = "[AwsAccountOrganizationMembershipProvider] ";
    private AWSOrganizations organizations = AWSOrganizationsClientBuilder.defaultClient();

    @Override
    public List<AccountOrganizationMembership> query(AccountOrganizationMembershipQuerySpecification querySpec)
            throws org.openeai.jms.consumer.commands.provider.ProviderException {
        if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
            String errMsg = "The StackId is null. The ExampleStackProvider" + "presently only implements query by StackId.";
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg);
        }
        List<AccountOrganizationMembership> accountOrganizationMemberships = new ArrayList<>();
        ListParentsRequest listParentsRequest = new ListParentsRequest();
        listParentsRequest.setChildId(querySpec.getAccountId());
        ListParentsResult listParentResults = organizations.listParents(listParentsRequest);
        for (Parent parent : listParentResults.getParents()) {
            AccountOrganizationMembership accountOrganizationMembership = null;
            try {
                accountOrganizationMembership = (AccountOrganizationMembership) appConfig
                        .getObjectByType(AccountOrganizationMembership.class.getName());
                accountOrganizationMemberships.add(accountOrganizationMembership);
                accountOrganizationMembership.setAccountId(parent.getId());
                accountOrganizationMembership.setParentId(parent.getType());
            } catch (EnterpriseConfigurationObjectException | EnterpriseFieldException e1) {
                logger.error(e1);
                throw new org.openeai.jms.consumer.commands.provider.ProviderException(e1.getMessage());
            }
        }
        return accountOrganizationMemberships;
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
            CreateOrganizationRequest createOrganizationRequest = new CreateOrganizationRequest();
            // createOrganizationRequest.set
            // organizations.createOrganization(createOrganizationRequest);
            // TODO: map req to request
            CreateOrganizationalUnitRequest request = new CreateOrganizationalUnitRequest();
            request.setParentId(req.getParentId());
            // request.setName(name);
            CreateOrganizationalUnitResult result = organizations.createOrganizationalUnit(request);
            OrganizationalUnit organizationalUnit = result.getOrganizationalUnit();
            organizationalUnit.getArn();
            organizationalUnit.getId();
            organizationalUnit.getName();
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
