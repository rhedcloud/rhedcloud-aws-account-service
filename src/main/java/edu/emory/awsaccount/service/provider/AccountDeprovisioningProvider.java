/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright (C) 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

// Core Java
import java.util.List;

// OpenEAI config foundation
import org.openeai.config.AppConfig;


// AWS Message Object API (MOA)
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountDeprovisioning;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountDeprovisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountDeprovisioningRequisition;
import com.service_now.moa.jmsobjects.servicedesk.v2_0.Incident;
import com.service_now.moa.objects.resources.v2_0.IncidentRequisition;

/**
 * Interface for all AccountDeprovisioning object providers.
 * <P>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 8 May 2020
 */
public interface AccountDeprovisioningProvider {
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
     * @param AccountDeprovisioningQuerySpecficiation, the query parameter.
     * @return List, a list of matching AccountDeprovisioning objects.
     *         <P>
     * @throws ProviderException
     *             with details of the providing the list.
     */
    public List<AccountDeprovisioning> query(AccountDeprovisioningQuerySpecification querySpec)
    	throws ProviderException;

    /**
     *
     * <P>
     *
     * @param AccountDeprovisioningRequisition, the generate parameter.
     * @return AccountDeprovisioning, a generated AccountDeprovisioning object
     * for the requisition.
     *         <P>
     * @throws ProviderException with details of the error generating the
     * AccountDeprovisioning object.
     */
    public AccountDeprovisioning generate(AccountDeprovisioningRequisition requisition)
    	throws ProviderException;

    /**
     *
     * <P>
     *
     * @param AccountDeprovisioning, the new AccountDeprovisioning object to create.
     *            <P>
     * @throws ProviderException with details of the error creating the
     * AccountDeprovisioning object.
     */
    public void create(AccountDeprovisioning ad) throws ProviderException;


    /**
     *
     * <P>
     *
     * @param AccountDeprovisioning, the new state of the AccountDeprovisioning
     * to update.
     *            <P>
     * @throws ProviderException with details of the error updating the
     * AccountDeprovisioning object.
     */
    public void update(AccountDeprovisioning ad) throws ProviderException;

    /**
     *
     * <P>
     *
     * @param AccountDeprovisioning, the object to delete.
     *            <P>
     * @throws ProviderException with details of the error deleting the
     * AccountDeprovisioning object.
     */
    public void delete(AccountDeprovisioning ad) throws ProviderException;

    /**
     *
     * <P>
     *
     * @param IncidentRequisition, the IncidentRequisition to generate an Incident.
     *            <P>
     * @throws ProviderException with details of the error generating the Incident.
     */
    public Incident generateIncident(IncidentRequisition req) throws ProviderException;

    /**
     *
     * <P>
     *
     * @param UserNotification, the UserNotification to send to all central admins.
     *            <P>
     * @throws ProviderException with details of the error sending the notification.
     */
    public int notifyCentralAdministrators(UserNotification notification) throws ProviderException;

    /**
     *
     * <P>
     *
     * @param List<String>, a list of central administrator user IDs.
     *            <P>
     * @throws ProviderException with details of the error retrieving the list of Ids.
     */
    public List<String> getCentralAdministrators() throws ProviderException;

}