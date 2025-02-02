package com.akto.action;


import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.akto.dao.*;
import com.akto.dto.*;
import com.opensymphony.xwork2.ActionSupport;
import org.bson.Document;
import org.bson.types.Code;
import org.bson.types.ObjectId;
import org.checkerframework.checker.units.qual.s;
import org.json.JSONObject;

import com.akto.dao.context.Context;
import com.akto.dao.test_editor.YamlTemplateDao;
import com.akto.dto.RBAC.Role;
import com.akto.dto.test_editor.YamlTemplate;
import com.akto.dto.type.SingleTypeInfo.SuperType;
import com.akto.listener.InitializerListener;
import com.akto.log.LoggerMaker;
import com.akto.log.LoggerMaker.LogDb;
import com.akto.mixpanel.AktoMixpanel;
import com.akto.util.DashboardMode;
import com.akto.util.EmailAccountName;
import com.mongodb.BasicDBObject;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;

public class CodeAnalysisAction extends ActionSupport {

    private String projectName;
    private String repoName;
    private boolean isLastBatch;
    private List<CodeAnalysisApi> codeAnalysisApisList;
    private CodeAnalysisRepo codeAnalysisRepo;
    public static final int MAX_BATCH_SIZE = 100;

    private static final LoggerMaker loggerMaker = new LoggerMaker(CodeAnalysisAction.class);

    public String syncExtractedAPIs() {
        String apiCollectionName = projectName + "/" + repoName;
        loggerMaker.infoAndAddToDb("Syncing code analysis endpoints for collection: " + apiCollectionName, LogDb.DASHBOARD);

        if (codeAnalysisApisList == null) {
            loggerMaker.errorAndAddToDb("Code analysis api's list is null", LogDb.DASHBOARD);
            addActionError("Code analysis api's list is null");
            return ERROR.toUpperCase();
        }

        // Ensure batch size is not exceeded
        if (codeAnalysisApisList.size() > MAX_BATCH_SIZE) {
            String errorMsg = "Code analysis api's sync batch size exceeded. Max Batch size: " + MAX_BATCH_SIZE + " Batch size: " + codeAnalysisApisList.size();
            loggerMaker.errorAndAddToDb(errorMsg, LogDb.DASHBOARD);
            addActionError(errorMsg);
            return ERROR.toUpperCase();
        }

        // update codeAnalysisRepo
        CodeAnalysisRepoDao.instance.updateOneNoUpsert(
                Filters.and(
                        Filters.eq(CodeAnalysisRepo.REPO_NAME, repoName),
                        Filters.eq(CodeAnalysisRepo.PROJECT_NAME, projectName)
                ),
                Updates.set(CodeAnalysisRepo.LAST_RUN, Context.now())
        );

        // populate code analysis api map
        Map<String, CodeAnalysisApi> codeAnalysisApisMap = new HashMap<>();
        for (CodeAnalysisApi codeAnalysisApi: codeAnalysisApisList) {
            codeAnalysisApisMap.put(codeAnalysisApi.generateCodeAnalysisApisMapKey(), codeAnalysisApi);
        }

        ApiCollection apiCollection = ApiCollectionsDao.instance.findByName(apiCollectionName);
        if (apiCollection == null) {
            apiCollection = new ApiCollection(Context.now(), apiCollectionName, Context.now(), new HashSet<>(), null, 0, false, false);
            ApiCollectionsDao.instance.insertOne(apiCollection);
        }

        /*
         * In some cases it is not possible to determine the type of template url from source code
         * In such cases, we can use the information from traffic endpoints to match the traffic and source code endpoints
         *
         * Eg:
         * Source code endpoints:
         * GET /books/STRING -> GET /books/AKTO_TEMPLATE_STR -> GET /books/INTEGER
         * POST /city/STRING/district/STRING -> POST /city/AKTO_TEMPLATE_STR/district/AKTO_TEMPLATE_STR -> POST /city/STRING/district/INTEGER
         * Traffic endpoints:
         * GET /books/INTEGER -> GET /books/AKTO_TEMPLATE_STR
         * POST /city/STRING/district/INTEGER -> POST /city/AKTO_TEMPLATE_STR/district/AKTO_TEMPLATE_STR
         */

        List<BasicDBObject> trafficApis = ApiCollectionsDao.fetchEndpointsInCollectionUsingHost(apiCollection.getId(), 0, -1,  60 * 24 * 60 * 60);
        Map<String, String> trafficApiEndpointAktoTemplateStrToOriginalMap = new HashMap<>();
        List<String> trafficApiKeys = new ArrayList<>();
        for (BasicDBObject trafficApi: trafficApis) {
            BasicDBObject trafficApiApiInfoKey = (BasicDBObject) trafficApi.get("_id");
            String trafficApiMethod = trafficApiApiInfoKey.getString("method");
            String trafficApiUrl = trafficApiApiInfoKey.getString("url");
            String trafficApiEndpoint = "";

            // extract path name from url
            try {
                // Directly parse the trafficApiUrl as a URI
                URI uri = new URI(trafficApiUrl);
                trafficApiEndpoint = uri.getPath();

                // Decode any percent-encoded characters in the path
                trafficApiEndpoint = java.net.URLDecoder.decode(trafficApiEndpoint, "UTF-8");

            } catch (Exception e) {
                loggerMaker.errorAndAddToDb("Error parsing URI: " + trafficApiUrl, LogDb.DASHBOARD);
                continue;
            }


            // Ensure endpoint doesn't end with a slash
            if (trafficApiEndpoint.length() > 1 && trafficApiEndpoint.endsWith("/")) {
                trafficApiEndpoint = trafficApiEndpoint.substring(0, trafficApiEndpoint.length() - 1);
            }

            String trafficApiKey = trafficApiMethod + " " + trafficApiEndpoint;
            trafficApiKeys.add(trafficApiKey);

            String trafficApiEndpointAktoTemplateStr = trafficApiEndpoint;

            for (SuperType type : SuperType.values()) {
                // Replace each occurrence of Akto template url format with"AKTO_TEMPLATE_STRING"
                trafficApiEndpointAktoTemplateStr = trafficApiEndpointAktoTemplateStr.replace(type.name(), "AKTO_TEMPLATE_STR");
            }

            trafficApiEndpointAktoTemplateStrToOriginalMap.put(trafficApiEndpointAktoTemplateStr, trafficApiEndpoint);
        }

        Map<String, CodeAnalysisApi> tempCodeAnalysisApisMap = new HashMap<>(codeAnalysisApisMap);
        for (Map.Entry<String, CodeAnalysisApi> codeAnalysisApiEntry: codeAnalysisApisMap.entrySet()) {
            String codeAnalysisApiKey = codeAnalysisApiEntry.getKey();
            CodeAnalysisApi codeAnalysisApi = codeAnalysisApiEntry.getValue();

            String codeAnalysisApiEndpoint = codeAnalysisApi.getEndpoint();

            String codeAnalysisApiEndpointAktoTemplateStr = codeAnalysisApiEndpoint;

            for (SuperType type : SuperType.values()) {
                // Replace each occurrence of Akto template url format with "AKTO_TEMPLATE_STRING"
                codeAnalysisApiEndpointAktoTemplateStr = codeAnalysisApiEndpointAktoTemplateStr.replace(type.name(), "AKTO_TEMPLATE_STR");
            }

            if(codeAnalysisApiEndpointAktoTemplateStr.contains("AKTO_TEMPLATE_STR") && trafficApiEndpointAktoTemplateStrToOriginalMap.containsKey(codeAnalysisApiEndpointAktoTemplateStr)) {
                CodeAnalysisApi newCodeAnalysisApi = new CodeAnalysisApi(
                        codeAnalysisApi.getMethod(),
                        trafficApiEndpointAktoTemplateStrToOriginalMap.get(codeAnalysisApiEndpointAktoTemplateStr),
                        codeAnalysisApi.getLocation());

                tempCodeAnalysisApisMap.remove(codeAnalysisApiKey);
                tempCodeAnalysisApisMap.put(newCodeAnalysisApi.generateCodeAnalysisApisMapKey(), newCodeAnalysisApi);
            }
        }


        /*
         * Match endpoints between traffic and source code endpoints, when only method is different
         * Eg:
         * Source code endpoints:
         * POST /books
         * Traffic endpoints:
         * PUT /books
         * Add PUT /books to source code endpoints
         */
        for(String trafficApiKey: trafficApiKeys) {
            if (!codeAnalysisApisMap.containsKey(trafficApiKey)) {
                for(Map.Entry<String, CodeAnalysisApi> codeAnalysisApiEntry: tempCodeAnalysisApisMap.entrySet()) {
                    CodeAnalysisApi codeAnalysisApi = codeAnalysisApiEntry.getValue();
                    String codeAnalysisApiEndpoint = codeAnalysisApi.getEndpoint();

                    String trafficApiMethod = "", trafficApiEndpoint = "";
                    try {
                        String[] trafficApiKeyParts = trafficApiKey.split(" ");
                        trafficApiMethod = trafficApiKeyParts[0];
                        trafficApiEndpoint = trafficApiKeyParts[1];
                    } catch (Exception e) {
                        loggerMaker.errorAndAddToDb("Error parsing traffic API key: " + trafficApiKey, LogDb.DASHBOARD);
                        continue;
                    }

                    if (codeAnalysisApiEndpoint.equals(trafficApiEndpoint)) {
                        CodeAnalysisApi newCodeAnalysisApi = new CodeAnalysisApi(
                                trafficApiMethod,
                                trafficApiEndpoint,
                                codeAnalysisApi.getLocation());

                        tempCodeAnalysisApisMap.put(newCodeAnalysisApi.generateCodeAnalysisApisMapKey(), newCodeAnalysisApi);
                        break;
                    }
                }
            }
        }

        codeAnalysisApisMap = tempCodeAnalysisApisMap;

        ObjectId codeAnalysisCollectionId = null;
        try {
            // ObjectId for new code analysis collection
            codeAnalysisCollectionId = new ObjectId();

            String projectDir = projectName + "/" + repoName;  //todo:

            CodeAnalysisCollection codeAnalysisCollection = CodeAnalysisCollectionDao.instance.updateOne(
                    Filters.eq("codeAnalysisCollectionName", apiCollectionName),
                    Updates.combine(
                            Updates.setOnInsert(CodeAnalysisCollection.ID, codeAnalysisCollectionId),
                            Updates.setOnInsert(CodeAnalysisCollection.NAME, apiCollectionName),
                            Updates.set(CodeAnalysisCollection.PROJECT_DIR, projectDir)
                    )
            );

            // Set code analysis collection id if existing collection is updated
            if (codeAnalysisCollection != null) {
                codeAnalysisCollectionId = codeAnalysisCollection.getId();
            }
        } catch (Exception e) {
            loggerMaker.errorAndAddToDb("Error updating code analysis collection: " + apiCollectionName + " Error: " + e.getMessage(), LogDb.DASHBOARD);
            addActionError("Error syncing code analysis collection: " + apiCollectionName);
            return ERROR.toUpperCase();
        }

        if (codeAnalysisCollectionId != null) {
            List<WriteModel<CodeAnalysisApiInfo>> bulkUpdates = new ArrayList<>();

            for(Map.Entry<String, CodeAnalysisApi> codeAnalysisApiEntry: codeAnalysisApisMap.entrySet()) {
                CodeAnalysisApi codeAnalysisApi = codeAnalysisApiEntry.getValue();
                CodeAnalysisApiInfo.CodeAnalysisApiInfoKey codeAnalysisApiInfoKey = new CodeAnalysisApiInfo.CodeAnalysisApiInfoKey(codeAnalysisCollectionId, codeAnalysisApi.getMethod(), codeAnalysisApi.getEndpoint());

                bulkUpdates.add(
                        new UpdateOneModel<>(
                                Filters.eq(CodeAnalysisApiInfo.ID, codeAnalysisApiInfoKey),
                                Updates.combine(
                                        Updates.setOnInsert(CodeAnalysisApiInfo.ID, codeAnalysisApiInfoKey),
                                        Updates.set(CodeAnalysisApiInfo.LOCATION, codeAnalysisApi.getLocation())
                                ),
                                new UpdateOptions().upsert(true)
                        )
                );
            }

            if (bulkUpdates.size() > 0) {
                try {
                    CodeAnalysisApiInfoDao.instance.getMCollection().bulkWrite(bulkUpdates);
                } catch (Exception e) {
                    loggerMaker.errorAndAddToDb("Error updating code analysis api infos: " + apiCollectionName + " Error: " + e.getMessage(), LogDb.DASHBOARD);
                    addActionError("Error syncing code analysis collection: " + apiCollectionName);
                    return ERROR.toUpperCase();
                }
            }
        }

        loggerMaker.infoAndAddToDb("Updated code analysis collection: " + apiCollectionName, LogDb.DASHBOARD);
        loggerMaker.infoAndAddToDb("Source code endpoints count: " + codeAnalysisApisMap.size(), LogDb.DASHBOARD);

        if (isLastBatch) {//Remove scheduled state from codeAnalysisRepo
            CodeAnalysisRepoDao.instance.updateOne(Filters.eq("_id", codeAnalysisRepo.getId()), Updates.set(CodeAnalysisRepo.LAST_RUN, Context.now()));
            loggerMaker.infoAndAddToDb("Updated last run for project:" + codeAnalysisRepo.getProjectName() + " repo:" + codeAnalysisRepo.getRepoName(), LogDb.DASHBOARD);
        }

        return SUCCESS.toUpperCase();
    }

    public List<CodeAnalysisApi> getCodeAnalysisApisList() {
        return codeAnalysisApisList;
    }

    public void setCodeAnalysisApisList(List<CodeAnalysisApi> codeAnalysisApisList) {
        this.codeAnalysisApisList = codeAnalysisApisList;
    }


    List<CodeAnalysisRepo> reposToRun = new ArrayList<>();
    public String findReposToRun() {
        reposToRun = CodeAnalysisRepoDao.instance.findAll(
                Filters.expr(
                        Document.parse("{ $gt: [ \"$" + CodeAnalysisRepo.SCHEDULE_TIME + "\", \"$" + CodeAnalysisRepo.LAST_RUN + "\" ] }")
                )
        );
        return SUCCESS.toUpperCase();
    }

    public List<CodeAnalysisRepo> getReposToRun() {
        return reposToRun;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public boolean isLastBatch() {
        return isLastBatch;
    }

    public void setLastBatch(boolean lastBatch) {
        isLastBatch = lastBatch;
    }

    public CodeAnalysisRepo getCodeAnalysisRepo() {
        return codeAnalysisRepo;
    }

    public void setCodeAnalysisRepo(CodeAnalysisRepo codeAnalysisRepo) {
        this.codeAnalysisRepo = codeAnalysisRepo;
    }
}
