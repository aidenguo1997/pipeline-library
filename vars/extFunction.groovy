#!/usr/bin/env groovy

import com.jayway.jsonpath.JsonPath


def baseFunction = load("baseFunction.groovy")


def modifyVersion(devVer){
    sh "sudo find ${env.WORKSPACE}/${testFolder}/src/test/java/features -name '*.feature' -type f -exec sed -i 's#def testVersion = 0#def testVersion = \\\"${devVer}\\\"#g' {} +"
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
                if (swaggerUrl.contains(testVer)){
                    endPoint = '"' + keys[i].replaceAll("/" + testVer + "/", "/" + devVer + "/") + '"'
                }  else {
                    error "swagger version and test case version are not same."
                }
            }  else {
                if (swaggerUrl.contains(devVer)){
                    endPoint = '"' + keys[i] + '"'
                }  else {
                    error "swagger version and test case version are not same."
                }
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
    // if (!mapRemove.isEmpty()){
    //     baseFunction.getRmEndPointResult(oldSwaggerKeys, oldMapSwagger, mapRemove, rmMapEndPoint)
    // }
    if (!strEndPoint.isEmpty()){
        if (strEndPoint[-1].trim() == ","){
            strEndPoint = strEndPoint.substring(0, strEndPoint.length()-1)
        }
    }
    return strEndPoint
}


def getFinalResult(devVer, testVer){
    oldSwaggerUrl = swaggerUrl.replaceAll("/" + devVer + "/", "/" + testVer + "/")
    strScenario = "";
    strEndPoint = "";
    tempResult = "";
    mapEndPoint = [:]
    rmMapEndPoint = [:]
    tempResult += baseFunction.readMe();
    baseFunction.copyReport(devVer);
    def summaryJson = readJSON file: "${report}/report_${devVer}/karate-reports/karate-summary-json.txt"
    passNum = summaryJson.scenariosPassed
    failNum = summaryJson.scenariosfailed
    listFeatures = JsonPath.read(summaryJson, '$.featureSummary[*].packageQualifiedName')
    for(feature in listFeatures){
        println "feature: " + feature
        def featureJson = readJSON file: "${env.WORKSPACE}/report/report_${devVer}/karate-reports/${feature}.karate-json.txt"
        JsonPath.read(featureJson, '$.scenarioResults[*]').each{
            scenarioResults = it
            strScenario += '{"name":"' + "${scenarioResults.name}" + '","durationMillis":"' + "${scenarioResults.durationMillis}" + '","failed":"' + "${scenarioResults.failed}" + '"},'
        }
        listStatus = JsonPath.read(featureJson, '$..status')
        listText = JsonPath.read(featureJson, '$..text')
        allMap = baseFunction.getKarateEndPoint(devVer, testVer, listStatus, listText, mapEndPoint, rmMapEndPoint);
        mapEndPoint = allMap[0]
        rmMapEndPoint = allMap[1]
    }
    if (strScenario[-1].trim() == ","){
        strScenario = strScenario.substring(0, strScenario.length()-1)
    }
    jsonTemplate = '{"resultDate":"' + "${summaryJson.resultDate}" + '","totalTime":"' + "${summaryJson.totalTime}" + '","version":"' + "${devVer}" + '","testCaseVersion":"' + "${testVer}" + '","scenariosPassed":"' + "${summaryJson.scenariosPassed}" + '","scenariosfailed":"' + "${summaryJson.scenariosfailed}" + '","scenarioResults":[' + "${strScenario}" + ']'
    oldMapSwagger = getSwaggerJson(devVer, testVer, oldSwaggerUrl, "old");
    println "oldMapSwagger: " + oldMapSwagger
    mapSwagger = getSwaggerJson(devVer, testVer, swaggerUrl, "new");
    strEndPoint += compareEndPoint(oldMapSwagger, mapSwagger, mapEndPoint, rmMapEndPoint);
    tempResult += jsonTemplate + ',"endPoint":[' + "${strEndPoint}" + ']},'
    println "strEndPoint: " + strEndPoint
    println "strScenario: " + strScenario
    println "return result: " + tempResult
    if (tempResult[-1].trim() == ","){
        tempResult = tempResult.substring(0, tempResult.length()-1);
        tempResult += "]"
    }
    println "final result: " + tempResult
    getEamilInfo(passNum, failNum)
    writeJSON file: "${report}/result.json", json: tempResult
}


def getEamilInfo(passNum, failNum){
    othersError = "";
    println "passNum: ${passNum}"
    println "failNum: ${failNum}"
    if (passNum == 0 && failNum == 0){
        passNum = 0
        failNum = 1
        othersError = "true";
    }
    int totalNum = passNum + failNum
    successRate = (passNum/totalNum)*100
    println "passNum:${passNum}"
    println "successRate:${successRate}"
    try {
        if (!fileExists("${emailTemplate}")){
            println "Create folder"
            sh "pwd"
            sh "mkdir ${emailTemplate}"
        }
        sh "sudo cp ${env.WORKSPACE}/${testFolder}/report/groovy-email-html.template ${emailTemplate}"
        if (othersError == "true"){
            sh "sudo sed -i 's#\${rooturl}\${build.url}Reports#\${rooturl}\${build.url}console#g' ${emailTemplate}/groovy-email-html.template"
        }
        sh "sudo sed -i 's/totalNum/${totalNum}/g' ${emailTemplate}/groovy-email-html.template"
        sh "sudo sed -i 's/passNum/${passNum}/g' ${emailTemplate}/groovy-email-html.template"
        sh "sudo sed -i 's/failNum/${failNum}/g' ${emailTemplate}/groovy-email-html.template"
        sh "sudo sed -i 's/successRate/${successRate}%/g' ${emailTemplate}/groovy-email-html.template"
    }
    catch (exc) {
        error "Add email information fail."
    }
}