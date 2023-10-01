#!/usr/bin/env groovy

import com.jayway.jsonpath.JsonPath


def readMe(){
    result = ""
    readMe = sh(returnStdout:true, script: "cat ${env.WORKSPACE}/${testFolder}/README.md |head -n 1 |sed 's/# //g'");
    readMe = readMe.trim();
    readMe = readMe.replace("\n", "")
    result += '[{"README":"' + "${readMe}" + '"},'
    return result
}


def copyReport(ver){
    if (!fileExists("${env.WORKSPACE}/${testFolder}/report/")){
        error "Test case repository not has report folder."
    }
    try {
        sh "cp -rfp ${env.WORKSPACE}/${testFolder}/report/ ${env.WORKSPACE}/"
        // Create report fodler, EX: report_1.0.0
        if (!fileExists("${report}/report_${ver}")){
            sh "mkdir -p ${report}/report_${ver}"
            sh "cp -rfp ${karaterReport} ${report}/report_${ver}"
        }
    } catch (exc) {
        error "Copy report fail."
    }
}


// Remove slash and combine path、method
// EX: every step text
def getKarateEndPoint(devVer, testVer, status, step, mapEndPoint, rmMapEndPoint){
    testResult = ""
    endPoint = "";
    stepNum = step.size();
    endPointTemp = ["path":0, "method":0]
    println "stepNum: " + stepNum
    // println "step: " + step
    // println "status: " + status 
    if (status.size().equals(step.size())){
        for (i=0; i<stepNum; i++){
            text = step[i].toLowerCase();
            testStatus = status[i]
            if (text.contains("path")){
                // Remove plus、less than、greater than、single quote、quote.
                text = text.replaceAll("\\+|\\<|\\>|\\'|\\\"","")
                // EX: path api/, testVersion, nextpublicholidaysworldwide
                // --> path api/, v3, nextpublicholidaysworldwide
                text = text.replaceAll("testversion", devVer)
                oldText = text.replaceAll("testversion", testVer)
                println "replace testVersion: " + text
                // Remove path slash space and comma
                // EX: path api/, v3, nextpublicholidaysworldwide
                // --> apiv3nextpublicholidaysworldwide
                endPoint = text.replaceAll("path\\s\\/|path\\s|\\,\\s|\\,","/")
                oldEndPoint = oldText.replaceAll("path\\s\\/|path\\s|\\,\\s|\\,","/")
                // println "endPoint remove path: " + endPoint
                endPoint = endPoint.replaceAll("\\s|/","")
                oldEndPoint = oldEndPoint.replaceAll("\\s|/","")
                endPointTemp["path"] = '"' + endPoint + '"'
            }
            if (endPointTemp[0] != 0 && text.contains("method")){
                endPoint = endPoint + "*" + text.replaceAll("method\\s|method","");
                endPointTemp.put("method", '"' + endPoint + '"')
            }
            if (testStatus == "skipped" || testStatus == "failed"){
                testResult = "FAIL";
            }
            if (testStatus == "passed"){
                testResult = "PASS";
            }
            if (i == (stepNum-1) && endPointTemp["path"] != 0 && endPointTemp["method"] != 0) {
                mapEndPoint.put('"' + endPoint + '"', '"' + testResult + '"')
                rmMapEndPoint.put('"' + endPoint + '"', '"' + testResult + '"')
            } else if (endPointTemp["path"] != 0 && endPointTemp["method"] != 0) {
                if (step.get(i+1).toLowerCase().contains("url") || step.get(i+1).toLowerCase().contains("path")) {
                    mapEndPoint.put('"' + endPoint + '"', '"' + testResult + '"')
                    rmMapEndPoint.put('"' + endPoint + '"', '"' + testResult + '"')
                    endPoint = "";
                    endPointTemp = ["path":0, "method":0]
                }
            }
            println "i: " + i + " text: " + text + " endPointTemp['path']: " + endPointTemp["path"] + " and endPointTemp['method']: " + endPointTemp["method"] + " testResult: " + testResult
        }
        println "final mapEndPoint: " + mapEndPoint;
    }
    return  [mapEndPoint, rmMapEndPoint]
}


def getTestResult(swaggerKeys, mapSwagger, compareKeys, mapEndPoint){
    strEndPoint = ""
    if (!compareKeys.isEmpty()){
        for (i=0; i<compareKeys.size(); i++){
            def(temp, method) = compareKeys[i].replaceAll("\\'|\\\"", "").split("\\*")
            endPoint = mapSwagger[compareKeys[i]]
            if (mapEndPoint.containsKey(compareKeys[i])){
                testResult = mapEndPoint[compareKeys[i]]
            } else if (! mapEndPoint.containsKey(compareKeys[i])){
                testResult = '"NOT TEST"';
            }
            strEndPoint += '{"strEndPoint":' + "${endPoint}" + ',"method":"' + "${method}" + '","result":' + "${testResult}" + '},'
        }
        println "compareEndPoint strEndPoint: " + strEndPoint
    }
    return  strEndPoint
}


def getRmEndPointResult(oldSwaggerKeys, oldMapSwagger, mapRemove, rmMapEndPoint){
    strEndPoint = ""
    // println "getRmEndPointResult oldSwaggerKeys: " + oldSwaggerKeys
    println "getRmEndPointResult oldMapSwagger: " + oldMapSwagger
    for (i=0; i<mapRemove.size(); i++){
        def(temp, method) = mapRemove[i].replaceAll("\\'|\\\"", "").split("\\*")
        if (oldSwaggerKeys.contains(mapRemove[i])){
            endPoint = oldMapSwagger[mapRemove[i]]
            testResult = rmMapEndPoint[mapRemove[i]]
            println "getRmEndPointResult endPoint: " + endPoint
            println "getRmEndPointResult testResult: " + testResult
            strEndPoint += '{"strEndPoint":' + "${endPoint}" + ',"method":"' + "${method}" + '","result":' + "${testResult}" + '},'
        }
    }
    println "getRmEndPointResult strEndPoint: " + strEndPoint
    return  strEndPoint
}


def removeFolder(){
    try {
        // sh "cd ${env.WORKSPACE} && rm -rf *"
        sh "cd ${env.WORKSPACE} && find * | grep -v report | xargs rm -rf"
    }
    catch (exc) {
        error "Remove folder fail."
    }
}
