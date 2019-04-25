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

// OpenEAI config foundation
import org.openeai.config.AppConfig;

import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountNotification;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountNotificationQuerySpecification;


/**
 * Interface for all AccountNotification object providers.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 March 2019
 */
public interface AccountNotificationProvider {
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
     * @param AccountNotificationQuerySpecficiation, the query parameter.
     * @return List, a list of matching AccountNotification objects.
     *         <P>
     * @throws ProviderException
     *             with details of the providing the list.
     */
    public List<AccountNotification> query(AccountNotificationQuerySpecification querySpec) 
    	throws ProviderException;  
    
    /**
     * 
     * <P>
 	 *
     * @param AccountNotification, the account notification 
     *         <P>
     * @throws ProviderException with details of the error creating the notification.
     */
    public void create(AccountNotification aNotification) 
    	throws ProviderException;
    
    /**
     * 
     * <P>
 	 *
     * @param AccountNotification, the account notification 
     *         <P>
     * @throws ProviderException with details of the error updating the notification.
     */
    public void update(AccountNotification aNotification) 
    	throws ProviderException;
    
    /**
     * 
     * <P>
 	 *
     * @param AccountNotification, the account notification 
     *         <P>
     * @throws ProviderException with details of the error deleting the notification.
     */
    public void delete(AccountNotification aNotification) 
    	throws ProviderException;
    
}