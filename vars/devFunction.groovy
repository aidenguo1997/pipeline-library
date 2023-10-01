#!/usr/bin/env groovy

import com.jayway.jsonpath.JsonPath


def baseFunction = load("baseFunction.groovy")


def getVers(){
    vers = sh(returnStdout:true, script: "git tag --sort=creatordate")
    vers = vers.trim();
    vers = vers.split("\n")
    println "vers: ${vers}"
    return vers
}


def getMainVer(listVer){
    println "listVer: ${listVer}"
    listTemp = listVer.split("\\.")
    println "listTemp: ${listTemp}"
    println "len: " + listTemp.size()
    if (listTemp.size() == 3){
        return "${listTemp[0]}${listTemp[1]}"
    } else {
        error "Incorrect version."
    }
}

def getTestVers(devVer, listTestVer){
    listNewTestVer = [];
    devMainVer = getMainVer(devVer)
    println "devMainVer: ${devMainVer}"
    for (ver in listTestVer){
        testMainVer = getMainVer(ver)
        println "testMainVer: ${testMainVer}"
        if (devMainVer == testMainVer){
            listNewTestVer.add(ver)
        }
    }
    if (listNewTestVer.isEmpty()){
        error "API version ${devMainVer}.x are not exist."
    }
    println "listNewTestVer: ${listNewTestVer}"
    return listNewTestVer
}



def countFail(summaryJson, failNum){
    if (summaryJson.featuresFailed != 0){
        failNum += 1;
        println "failNum: ${failNum}"
    }
    return failNum
}


// Result: "v3railtranetwork*get":"/v3/Rail/TRA/Network"
def getSwaggerJson(devVer, testVer, swaggerUrl, version){
    mapTemp = [:]
    try {
        def response = httpRequest(url: swaggerUrl, timeout: 30)
        swaggerJson = readJSON text: response.content;
        paths = JsonPath.read(response.content, '$.paths')
        keys = paths.keySet()
        for (i=0;i<keys.size();i++){
            if (version == "old"){
                endPoint = '"' + keys[i].replaceAll("/" + testVer + "/", "/" + devVer + "/") + '"'
            }  else {
                endPoint = '"' + keys[i] + '"'
            }
            method = paths[keys[i]].keySet()
            for (j=0;j<method.size();j++){
                if (version == "old"){
                    endPointMethod = '"' + keys[i].replaceAll("/" + testVer + "/", "/" + devVer + "/") + "*" + method[j].replaceAll("\\[|\\]", "") + '"'
                } else {
                    endPointMethod = '"' + keys[i] + "*" + method[j].replaceAll("\\[|\\]", "") + '"'
                }
                rmSymEndPoint = endPointMethod.toString().toLowerCase().replaceAll("/|\\{|\\}", "")
                mapTemp.put(rmSymEndPoint, endPoint)
            }
        }
    }
    catch (exc) {
        error "Get swagger JSON fail."
    }
    return mapTemp
}


def compareEndPoint(oldMapSwagger, mapSwagger, mapEndPoint, rmMapEndPoint){
    swaggerKeys = mapSwagger.keySet()
    oldSwaggerKeys = oldMapSwagger.keySet()
    endPointKeys = mapEndPoint.keySet()
    mapCommon = swaggerKeys.intersect(endPointKeys)
    mapAdd = swaggerKeys.minus(endPointKeys)
    mapRemove = endPointKeys.minus(swaggerKeys)
    mapCommonAdd = swaggerKeys.minus(mapRemove)
    // println "compareEndPoint swaggerKeys: " + swaggerKeys
    // println "compareEndPoint endPointKeys: " + endPointKeys
    println "compareEndPoint mapCommon: " + mapCommon
    // println "compareEndPoint mapAdd: " + mapAdd
    println "compareEndPoint mapRemove: " + mapRemove
    // println "compareEndPoint mapCommonAdd: " + mapCommonAdd
    println "compareEndPoint rmMapEndPoint: " + rmMapEndPoint
    // baseFunction.getTestResult(swaggerKeys, mapSwagger, mapCommonAdd, mapEndPoint);
    strEndPoint += baseFunction.getTestResult(swaggerKeys, mapSwagger, mapCommon, mapEndPoint);
    strEndPoint += baseFunction.getTestResult(swaggerKeys, mapSwagger, mapAdd, mapEndPoint);
    strEndPoint += baseFunction.getRmEndPointResult(oldSwaggerKeys, oldMapSwagger, mapRemove, rmMapEndPoint);
    if (!strEndPoint.isEmpty()){
        if (strEndPoint[-1].trim() == ","){
            strEndPoint = strEndPoint.substring(0, strEndPoint.length()-1)
        }
    }
    return strEndPoint
}


def getFinalResult(devVer, testVers){
    strScenario = "";
    strEndPoint = "";
    tempResult = "";
    testTimes = 0
    failNum = 0
    mapEndPoint = [:]
    rmMapEndPoint = [:]
    println "getFinalResult devVer: " + devVer
    println "getFinalResult testVers: " + testVers
    testTimes = testVers.size()
    println "getFinalResult testTimes: " + testTimes
    tempResult += baseFunction.readMe();
    for (ver in testVers){
        initialVer = ver.split("\\.")
        initialVer = "${initialVer[1]}${initialVer[2]}"
        println "initialVer: ${initialVer}"
        oldSwaggerUrl = swaggerUrl.replaceAll("/" + devVer + "/", "/" + ver + "/")
        try {
            sh "git checkout ${ver}"
            sh "mvn -Dmaven.test.failure.ignore=true clean package"
        }
        catch (exc) {
            error "Running test case fail."
        }
        baseFunction.copyReport(ver);
        def summaryJson = readJSON file: "${report}/report_${ver}/karate-reports/karate-summary-json.txt"
        listFeatures = JsonPath.read(summaryJson, '$.featureSummary[*].packageQualifiedName')
        for(feature in listFeatures){
            println "feature: " + feature
            def featureJson = readJSON file: "${env.WORKSPACE}/report/report_${ver}/karate-reports/${feature}.karate-json.txt"
            JsonPath.read(featureJson, '$.scenarioResults[*]').each{
                scenarioResults = it
                strScenario += '{"name":"' + "${scenarioResults.name}" + '","durationMillis":"' + "${scenarioResults.durationMillis}" + '","failed":"' + "${scenarioResults.failed}" + '"},'
            }
            listStatus = JsonPath.read(featureJson, '$..status')
            listText = JsonPath.read(featureJson, '$..text')
            baseFunction.getKarateEndPoint(devVer, ver, listStatus, listText, mapEndPoint, rmMapEndPoint);
            println "strScenario feature: " + feature + "\n" + strScenario
        }
        if (strScenario[-1].trim() == ","){
            strScenario = strScenario.substring(0, strScenario.length()-1)
        }
        jsonTemplate = '{"resultDate":"' + "${summaryJson.resultDate}" + '","totalTime":"' + "${summaryJson.totalTime}" + '","version":"' + "${ver}" + '","scenariosPassed":"' + "${summaryJson.scenariosPassed}" + '","scenariosfailed":"' + "${summaryJson.scenariosfailed}" + '","scenarioResults":[' + "${strScenario}" + ']'
        if (initialVer == "00"){
            println "into initialVer"
            oldMapSwagger = getSwaggerJson(devVer, ver, oldSwaggerUrl, "old");
            mapSwagger = getSwaggerJson(devVer, ver, swaggerUrl, "new");
            compareEndPoint(oldMapSwagger, mapSwagger, mapEndPoint, rmMapEndPoint);
            tempResult += jsonTemplate + ',"endPoint":[' + "${strEndPoint}" + ']},'
        } else {
            tempResult += jsonTemplate + '},'
        }
        println "strEndPoint: " + strEndPoint
        println "strScenario: " + strScenario
        println "tempResult: " + tempResult
        failNum = countFail(summaryJson, failNum);
        strEndPoint = ""
        strScenario = ""
    }
    if (tempResult[-1].trim() == ","){
        println "tempResult -1: " + tempResult
        tempResult = tempResult.substring(0, tempResult.length()-1);
        println "tempResult replace comma: " + tempResult
        tempResult += "]"
        println "final tempResult: " + tempResult
    }
    getEamilInfo(testTimes, failNum);
    writeJSON file: "${report}/result.json", json: tempResult
}


def getEamilInfo(testTimes, failNum){
    othersError = "";
    int passNum = testTimes-failNum
    if (passNum == 0 && failNum == 0){
        passNum = 0
        failNum = 0
        testTimes = 0
        othersError = "true";
    }
    if (testTimes == 0){
        successRate = 0
    } else {
        successRate = (passNum/testTimes)*100
    }
    println "passNum:${passNum}"
    println "successRate:${successRate}"
    try {
        if (!fileExists("${emailTemplate}")){
            println "Create folder"
            sh "pwd"
            sh "mkdir ${email-templates}"
        }
        sh "sudo cp ${env.WORKSPACE}/report/groovy-email-html.template ${emailTemplate}"
        if (othersError == "true"){
            sh "sudo sed -i 's#\${rooturl}\${build.url}Reports#\${rooturl}\${build.url}console#g' ${emailTemplate}/groovy-email-html.template"
        }
        sh "sudo sed -i 's/testTimes/${testTimes}/g' ${emailTemplate}/groovy-email-html.template"
        sh "sudo sed -i 's/passNum/${passNum}/g' ${emailTemplate}/groovy-email-html.template"
        sh "sudo sed -i 's/failNum/${failNum}/g' ${emailTemplate}/groovy-email-html.template"
        sh "sudo sed -i 's/successRate/${successRate}%/g' ${emailTemplate}/groovy-email-html.template"
    }
    catch (exc) {
        error "Add email information fail."
    }
}


def stopDocker(){
    println "stopDocker"
    listContainer = sh(returnStdout:true, script: "docker ps -a | grep -i api | wc -l");
    println "listContainer: " + listContainer
    if (listContainer.trim() != "0"){
        sh "docker stop ${dockerName}"
        sh "docker rm ${dockerName}"
    }
}

