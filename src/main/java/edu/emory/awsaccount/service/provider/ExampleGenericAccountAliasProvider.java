/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

// Java utilities
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;

// Log4j
import org.apache.log4j.Category;

// JDOM
import org.jdom.Document;
import org.jdom.Element;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.consumer.commands.provider.ExampleCrudProvider;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

//AWS Message Object API (MOA)
import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountOrganizationMembership;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountOrganizationMembershipQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;

/**
 * An example object provider that maintains an in-memory store of stacks.
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 12 July 2018
 *
 */
public class ExampleGenericAccountAliasProvider extends ExampleCrudProvider<AccountAlias, AccountAliasQuerySpecification> {
}
