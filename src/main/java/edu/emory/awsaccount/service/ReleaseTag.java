/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016-2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service;

/**
 * A release tag for the Emory AWS Account Service.
 * <P>
 * @author      Steve Wheat (swheat@emory.edu)
 * @version     1.0  - 4 June 2016
 */
public abstract class ReleaseTag {

  public static String space = " ";
  public static String notice = "***";
  public static String releaseName = "Emory AWS Account Service";
  public static String releaseNumber = "Release 1.0";
  public static String buildNumber = "Build ####";
  public static String copyRight = "Copyright 2016 Emory University." +
    " All Rights Reserved.";
	
  public static String getReleaseInfo() {
    StringBuffer sbuf = new StringBuffer();
    sbuf.append(notice);
    sbuf.append(space);
    sbuf.append(releaseName);
    sbuf.append(space);
    sbuf.append(releaseNumber);
    sbuf.append(space);
    sbuf.append(buildNumber);
    sbuf.append(space);
    sbuf.append(copyRight);
    sbuf.append(space);
    sbuf.append(notice);
    return sbuf.toString();
  }
}
