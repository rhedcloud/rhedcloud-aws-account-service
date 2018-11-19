# My project's README

ElasticIpService is now updated on dev, with ElasticIpAssignmentRequest command as defaultCommand, handles all operations (Generate, Update, Delete, Query, Create) ElasticIpAssignment message object.  Query and Create are just proxy which delegates to RdbmsRequestCommand.  For ElasticIP objects, it should go to RdbmsRequestCommand.

