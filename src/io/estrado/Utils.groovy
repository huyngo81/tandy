package com.transcendinsights.jenkinspipeline

@Grab('com.oblac:nomen-est-omen:1.2.2')
import com.oblac.nomen.Nomen
import groovy.text.SimpleTemplateEngine
import groovy.text.TemplateEngine
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.json.JsonBuilder
import groovy.transform.Field
import groovy.xml.MarkupBuilder
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder
import hudson.Util
import net.sf.json.JSONArray
import net.sf.json.JSONObject

def templateForJava(String label, Closure closure) {
  template(label, closure, 'ti-dockeri.rsc.humad.com/transcendinsights/javabuild:207')
}

def templateForGroovy(String label, Closure closure) {
  template(label, closure, 'ti-dockeri.rsc.humad.com/transcendinsights/ti-groovybuild:54')
}

def templateForNode(String label, Closure closure) {
  template(label, closure, 'ti-dockeri.rsc.humad.com/transcendinsights/node8build:193')
}

def template(String label, Closure closure, String templateImage) {
  podTemplate(
      label: label,
      containers: [
          containerTemplate(name: 'jnlp',
              image: templateImage,
              command: 'jenkins-entrypoint',
              args: '${computer.jnlpmac} ${computer.name}',
              resourceLimitCpu: '2000m',
              resourceLimitMemory: '6144Mi',
          ),
      ],
      volumes: [
          hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
          secretVolume(mountPath: '/home/jenkins/.docker', secretName: 'docker-pass'),
          secretVolume(mountPath: '/tmp/gradle', secretName: 'gradle-secret'),
          secretVolume(mountPath: '/home/jenkins/kube-rel', secretName: 'admin-conf-rel-secret'),
          secretVolume(mountPath: '/home/jenkins/kube-perf', secretName: 'admin-conf-perf-secret'),
      ],
      serviceAccount: 'jenkins',
      closure
  )
}

def suggestReviewers() {
  def reviewers = sh(script: "/bin/git-reviewers.sh", returnStdout: true)
  def changeUrlParts = env.CHANGE_URL.split('/')

  try {
    def ghApiUrl = System.getenv('GITHUB_API_URL')
    def ghUser = System.getenv('GITHUB_USER')
    def ghPass = System.getenv('GITHUB_PASS')
    GitHub github = GitHubBuilder
        .fromEnvironment()
        .withPassword(ghUser, ghPass)
        .withEndpoint(ghApiUrl)
        .build()

    def repo = github.getRepository(changeUrlParts[-4..-3].join('/'))
    def pr = repo.getPullRequest(changeUrlParts[-1].toString().toInteger())

    pr.listComments().asList().
        findAll {
          def reviewStart = 'Suggested Reviewers\n===================\n'
          it.user.login.equalsIgnoreCase(ghUser) && it.body.startsWith(reviewStart)
        }.
        each { it.delete() }
    pr.comment(reviewers)

  } catch (Exception e) {
    println "Exception occurred while suggesting reviewers: $e"
  }

}

def configGradleProperties() {
  sh '''\
      if [ "$(whoami)" = "root" ]; then
          mkdir -p /root/.gradle
          cat /tmp/gradle/gradle.properties > /root/.gradle/gradle.properties
          chmod a+r /root/.gradle/gradle.properties
      fi
      mkdir -p /home/jenkins/.gradle
      cat /tmp/gradle/gradle.properties > /home/jenkins/.gradle/gradle.properties
      chmod a+r /home/jenkins/.gradle/gradle.properties
      '''
}

def gradleSonar(String prId = null) {
  configGradleProperties()
  if (prId) {
    sh "./gradlew sonar -DprId=$prId"
  } else {
    sh "./gradlew sonar"
  }
}

def sendNotifications(String channel = slackChannel, Closure closure) {
  if (env.BRANCH_NAME == 'master') {
    notifyOfBuild channel, 'STARTED'
  }
  try {
    closure.call()
  } catch (e) {
    currentBuild.result = "FAILED"
    throw e
  } finally {
    if (currentBuild.result != 'SUCCESSFUL') {
      notifyOfBuild channel, currentBuild.result
    }
  }
}

String img(String url) {
  def ioniconsBase = 'https://cdnjs.cloudflare.com/ajax/libs/ionicons/2.0.1'
  def sw = new StringWriter()
  def mk = new MarkupBuilder(sw)
  mk.img(src: "${ioniconsBase}${url}", height: '16px')
  sw.toString()
}

private String getAbbreviatedJobName() {
  "${env.JOB_NAME}/${env.BUILD_NUMBER}".
      replaceAll('^TranscendInsights/', '').
      replaceAll('/master/', ' v')
}

private String getEmailJobName() {
  abbreviatedJobName.replaceAll('/PR-', ' PR ')
}

private String getChatJobName() {
  abbreviatedJobName.replaceAll('/PR-', ' PR #')
}

String getSimplifiedBranchName() {
  "${env.BRANCH_NAME}".replaceAll('/', '-').
      replaceAll('%2F', '-').
      replaceAll('\\.', '')
}

def notifyOfBuild(String channel = slackChannel, String buildStatus = 'STARTED') {
  // Pretty sure this line is irrelevant
  buildStatus = buildStatus ?: 'SUCCESSFUL'

  // Send email notification for build success or failure
  if (buildStatus != 'STARTED') {
    notifyEmailOfBuild(buildStatus)
  }

  // Don't send a notification for successful builds of non-master branches
  if (env.BRANCH_NAME == 'master' || buildStatus != 'SUCCESSFUL') {
    if(channel) {
      notifySlackOfBuild(buildStatus, channel)
    }
  }
}

def notifyEmailOfBuild(String buildStatus) {
  def emailEmojis = [
      STARTED: '⚡',
      SUCCESS: '✅',
      FAILURE: '🔴',
  ]
  emailext subject: "${emailEmojis[buildStatus] ?: buildStatus} ${emailJobName}",
        body: '$DEFAULT_CONTENT',
        to: '$DEFAULT_RECIPIENTS',
        mimeType: 'text/plain',
        recipientProviders: [
            [$class: 'DevelopersRecipientProvider'],
            [$class: 'CulpritsRecipientProvider'],
            [$class: 'RequesterRecipientProvider']
        ]
}

def getBuildCause() {
  def SCMCause = currentBuild.rawBuild.getCause(hudson.triggers.SCMTrigger$SCMTriggerCause)
  def UserCause = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
  def cause

  if (SCMCause) {
    cause = SCMCause.getShortDescription()
  } else if (UserCause) {
    cause = UserCause.getShortDescription()
  } else {
    cause = 'cause not found'
  }
  return cause
}

def notifySlackOfBuild(String buildStatus = 'STARTED', String channel = slackChannel) {
  def statusImages = [
      STARTED : 'https://raw.githubusercontent.com/jenkinsci/modernstatus-plugin/master/src/main/webapp/16x16/nobuilt.png',
      SUCCESS : 'https://raw.githubusercontent.com/jenkinsci/modernstatus-plugin/master/src/main/webapp/16x16/blue.png',
      FAILURE : 'https://raw.githubusercontent.com/jenkinsci/modernstatus-plugin/master/src/main/webapp/16x16/red.png',
      UNSTABLE: 'https://raw.githubusercontent.com/jenkinsci/modernstatus-plugin/master/src/main/webapp/16x16/yellow.png',
      ABORTED : 'https://raw.githubusercontent.com/jenkinsci/modernstatus-plugin/master/src/main/webapp/16x16/aborted.png',
  ]

  def colors = [
      'STARTED' : 'warning',
      'SUCCESS' : 'good',
      'FAILURE' : 'danger',
      'ABORTED' : 'warning',
      'UNSTABLE': 'warning',
  ]
  def color = colors[buildStatus] ?: 'danger'
  build_cause = buildCause()

  println buildStatus + ': ' + build_cause
  /*
  // Build the basic message
  JSONObject attachment = new JSONObject()
  attachment.put('author_icon', statusImages[buildStatus])
  attachment.put('author_link', env.RUN_DISPLAY_URL)
  attachment.put('author_name', chatJobName)
  attachment.put('fallback', buildStatus + ' ' + build_cause)
  attachment.put('color', color)
  //attachment.put('pretext', 'something?')

  if (buildStatus == 'STARTED') {
    // A simple message for job started
    attachment.put('text', build_cause)
  } else {
    // Details for job result
    JSONArray msg_fields = new JSONArray()

    JSONObject cause = new JSONObject()
    cause.put('title', 'Cause')
    cause.put('value', build_cause)
    cause.put('short', true)
    msg_fields.add(cause)

    if (buildStatus == 'SUCCESS' || buildStatus == 'FAILURE') {
      JSONObject duration = new JSONObject()
      duration.put('title', 'Duration')
      duration.put('value', Util.getTimeSpanString(currentBuild.duration))
      duration.put('short', true)
      msg_fields.add(duration)
    }

    JSONObject commit_message = new JSONObject()
    commit_message.put('title', 'Commit Message')
    commit_message.put('value', sh(returnStdout: true, script: 'git log -1 --pretty="%h %an %s%n%n%b"').trim())
    commit_message.put('short', false)
    msg_fields.add(commit_message)

    JSONObject status = new JSONObject()
    status.put('title', 'Status')
    if (buildStatus == 'FAILURE') {
      status.put('value', currentBuild.currentResult)
      status.put('short', false)
    } else {
      status.put('value', currentBuild.result)
      status.put('short', true)
    }
    msg_fields.add(status)

    attachment.put('fields', msg_fields)
  }

  // Add attachment and send
  JSONArray msg_attachments = new JSONArray()
  msg_attachments.add(attachment)
  slackSend color: color, channel: channel, attachments: msg_attachments.toString()
  */
}

def notifyOfDeployment(String channel = slackChannel, String namespace, String submitter) {
  def slack_link = "<${env.RUN_DISPLAY_URL}|${chatJobName}>"
  if (submitter) {
    slack_summary = "${submitter} pushed ${slack_link} to ${namespace}."
  } else {
    slack_summary = "${slack_link} pushed to ${namespace}."
  }
  println slack_summary
  /*
  if(channel) {
    slackSend color: '#800080', channel: channel, message: slack_summary
  }
  */
}

def kubeDeploy(Map<String, Object> attributes = [:],
               String templateFilename, String namespace, String version) {
  def envCommands = []
  def envList = ''
  attributes.each { k, v ->
    envCommands << "export $k='${v}'"
    envList += ' $' + k
  }
  def commands = envCommands + [
      "",
      "envsubst '${envList}' < ${templateFilename} > deploy.yaml",
      "mv deploy.yaml ${templateFilename}",
      "",
      "cat ${templateFilename}"
  ]
  sh commands.join('\n')
  try {
    String serviceName = attributes.serviceName ?: templateFilename[0..-6]
    def yamlTemplate = readFile(templateFilename).toString()

    println "deploying $serviceName with templateFile: $templateFilename"

    def deploymentConfig = readFile('deployment-config.groovy').toString()
    String yaml = generateDeployableYaml(namespace, version, serviceName, deploymentConfig, yamlTemplate)
    writeFile(file: 'deploy.yaml', text: yaml)
  } catch (e) {
    StringWriter sw = new StringWriter()
    e.printStackTrace(new PrintWriter(sw))
    println sw.toString()
    throw e
  }

  envCommands = []
  // TODO: KUBECONFIG value should come from environment variable. Hardcode for now.
  if(namespace == 'release') {
    envCommands << "export KUBECONFIG=/home/jenkins/kube-rel/admin-release.conf"
  }
  if(namespace == 'phm-perf') {
    envCommands << "export KUBECONFIG=/home/jenkins/kube-perf/admin-perf.conf"
  }
  commands = envCommands + [
      "",
      "cat deploy.yaml",
      "",
      "kubectl apply -f deploy.yaml"
  ]
  sh commands.join('\n')
}

@NonCPS
def getDeploymentTemplatePath(String templateFilename) {
  try {
    def matchPath = templateFilename =~ /^(.+\/)*(.+)\.(yaml)$/
    return matchPath[0]
  }catch (Exception e) {
    println "Exception occurred while retrieving template info: $e"
  }
}

def kubeDeployMultiple(Map<String, Object> attributes = [:],
                       String templateFilename, String namespace, String version) {

  // Preparation part
  def envCommands = []
  def envList = ''
  def templatePath = ''
  attributes.each { k, v ->
    envCommands << "export $k='${v}'"
    envList += ' $' + k
  }
  def commands = envCommands + [
      "",
      "envsubst '${envList}' < ${templateFilename} > deploy.yaml",
      "mv deploy.yaml ${templateFilename}",
      "",
      "cat ${templateFilename}"
  ]
  sh commands.join('\n')
  try {
    // Deployment template files that may reside in a subdirectory
    templatePath = getDeploymentTemplatePath(templateFilename)[1] ?: ''
    String serviceName = attributes.serviceName ?: getDeploymentTemplatePath(templateFilename)[2]
    def yamlTemplate = readFile(templateFilename).toString()
    println "deploying $serviceName with templateFile: $templateFilename"

    def deploymentConfig = readFile(templatePath + 'deployment-config.groovy').toString()
    String yaml = generateDeployableYaml(namespace, version, serviceName, deploymentConfig, yamlTemplate)
    writeFile(file: templatePath + 'deploy.yaml', text: yaml)
  } catch (e) {
    StringWriter sw = new StringWriter()
    e.printStackTrace(new PrintWriter(sw))
    println sw.toString()
    throw e
  }

  // Deployment part
  envCommands = []
  // TODO: KUBECONFIG value should come from environment variable. Hardcode for now.
  if(namespace == 'release') {
    envCommands << "export KUBECONFIG=/home/jenkins/kube-rel/admin-release.conf"
  }
  if(namespace == 'phm-perf') {
    envCommands << "export KUBECONFIG=/home/jenkins/kube-perf/admin-perf.conf"
  }
  commands = envCommands + [
      "",
      "cat ${templatePath}deploy.yaml",
      "",
      "kubectl apply -f ${templatePath}deploy.yaml"
  ]
  sh commands.join('\n')
}


@NonCPS
String generateDeployableYaml(String namespace, String version, String serviceName,
                              String deploymentConfig, String yamlTemplate) {
  ConfigObject config = new ConfigSlurper().parse(deploymentConfig)
  ConfigObject namespaceConfig = config.namespace[namespace]
  ConfigObject serviceConfig = namespaceConfig.service[serviceName]
  serviceConfig.version = version
  TemplateEngine templateEngine = new SimpleTemplateEngine()
  Map binding = ['ns': namespace, 'namespace': namespaceConfig, 'service': serviceConfig]
  templateEngine.createTemplate(yamlTemplate).make(binding).toString()
}

def archivePodLogs(String namespaceId) {
  try {
    sh(returnStdout: true, script: "kubectl -n ${namespaceId} get pods -o custom-columns=:metadata.name").trim().split().each { pod ->
      sh "kubectl logs -n ${namespaceId} ${pod} --tail -1 > ${pod}.log"
      archiveArtifacts(artifacts: "${pod}.log")
    }
  } catch (Exception exception) {
    System.err.println "Error archiving the pod logs: $exception"
    exception.printStackTrace()
  }
}

@Field String dockerImage

def dockerPush() {
  sh "docker push $dockerImage"
}

def clair() {
  sh "DOCKER_API_VERSION=1.24 clairctl analyze --local $dockerImage --config /clairctl.yml || echo 'Could not analyze'"
  sh "DOCKER_API_VERSION=1.24 clairctl report --local $dockerImage --config /clairctl.yml || echo 'Could not report'"
  sh "DOCKER_API_VERSION=1.24 clairctl delete --local $dockerImage --config /clairctl.yml || echo 'Could not delete'"

  archive includes: 'build/reports/clair/html/*.html'
}

//This is the new function we will use going forward once which will deprecate dockerPush
def dockerPushImage(String imageName) {
  sh "docker push $imageName"
}

//This is the new function we will use going forward once which will deprecate clair
def clairCheck(String imageName) {
  sh "DOCKER_API_VERSION=1.24 clairctl analyze --local $imageName --config /clairctl.yml || echo 'Could not analyze'"
  sh "DOCKER_API_VERSION=1.24 clairctl report --local $imageName --config /clairctl.yml || echo 'Could not report'"
  sh "DOCKER_API_VERSION=1.24 clairctl delete --local $imageName --config /clairctl.yml || echo 'Could not delete'"

  archive includes: 'build/reports/clair/html/*.html'
}

def smokeTest(String address, String channel = slackChannel, String environment, String project) {
  try {
    def responseCode = "curl -f -s -o /dev/null -w %{http_code} ${address}".execute().text as int
    if (responseCode == 200) {
      message_text = "${environment} smoke test passed for ${chatJobName}."
      println message_text
      /*
      if(channel) {
        slackSend message: message_text, color: 'good', channel: channel
      }
      */
    } else {
      throw new RuntimeException("${environment} smoke test failed for ${chatJobName}.")
    }
  } catch (Exception e) {
    message_text = "${environment} smoke test failed for ${chatJobName}. Reverting to previous stable version"
    println message_text
    /*
    if(channel) {
      slackSend message: message_text, color: 'danger', channel: channel
    }
    */
    "kubectl -n ${environment} rollout undo deployment/${project}".execute().text
    currentBuild.result = "FAILED"
    throw e
  }
}

String randomId() {
  Nomen.est().adjective().pokemon().get().replace('_','-').replaceAll('[^a-zA-Z0-9\\-]', '')
}

def postCommentToGithubPullRequest(String comment) {
  def changeUrlParts = env.CHANGE_URL.split('/')

  try {
    def ghApiUrl = System.getenv('GITHUB_API_URL')
    def ghUser = System.getenv('GITHUB_USER')
    def ghPass = System.getenv('GITHUB_PASS')
    GitHub github = GitHubBuilder
        .fromEnvironment()
        .withPassword(ghUser, ghPass)
        .withEndpoint(ghApiUrl)
        .build()

    def repo = github.getRepository(changeUrlParts[-4..-3].join('/'))
    def pr = repo.getPullRequest(changeUrlParts[-1].toString().toInteger())

    pr.comment(comment)

  } catch (Exception e) {
    println "Exception occurred while posting comment to PR: $e"
  }
}

def postPerfTestResults(List<String> simulations) {
  def jenkinsJobUrl = "http://jenkins.tools.t8i7.net/job/TranscendInsights/job/${env.JOB_NAME}/job/master/lastSuccessfulBuild/gatling/report/"

  def perfTestResults = ''
  def prStatFiles = findFiles(glob: '**/global_stats.json')

  if (prStatFiles) {
    simulations.each {
      simulation ->
        perfTestResults = "${perfTestResults} \n\n ${simulation} \n Mean Request/sec "
        def prStatFile = prStatFiles.find { it.path.contains(simulation) }

        if (prStatFile) {
          def prStats = readJSON file: "${prStatFile}"
          perfTestResults = "${perfTestResults}\n \t PR     :  ${prStats.meanNumberOfRequestsPerSecond.total}"
        }

        def masterStatFileName = 'global_stats.json'

        try {
          sh "wget -q ${jenkinsJobUrl}${simulation}/source/js/${masterStatFileName}"
          if (fileExists(masterStatFileName)) {
            def masterBuildStats = readJSON file: masterStatFileName
            perfTestResults = "${perfTestResults}\n \t Master : ${masterBuildStats.meanNumberOfRequestsPerSecond.total}"
            sh "rm ${masterStatFileName}"
          }
        } catch (Exception e) {
          println "Master file does not exist"
          perfTestResults = "${perfTestResults}\n \t Master : N/A"
        }
    }
  }
  if (perfTestResults) {
    perfTestResults = "``` \n ${perfTestResults} \n ``` "
    postCommentToGithubPullRequest(perfTestResults)
  }
}

@Field String nodeName
@Field String stashName
@Field String yamlTemplate
@Field String slackChannel
@Field String versionIdentifier

/**
 * Creates a series of opinionated deployments.
 *
 * The TI deployment looks like this for different branches:
 *
 * <pre><code>
 *     *** Single App  ***
 * develop:
 * ---+-(dev)---(int)--------+----
 *    |                      |
 *    +--------(perf)--------+
 *    |                      |
 *    +--------(icd)---------+
 *    |                      |
 *    +--------(phm-perf)----+
 * release/x.x.0:
 *    +------(release)-------+
 * hotfix/x.x.1:
 *    +------(hotfix)---------+
 * master:
 *    +------(release)-------+
 *
 *    *** Multiple App ***
 * develop:
 * --+---(dev)---(int)--------+----
 *   |   |app1|  |app1|       |
 *   |   | .  |  | .  |       |
 *   |   |appN|  |appN|       |
 *   +--------(icd)-----------+
 *   |        |app1|          |
 *   |        | .  |          |
 *   |        |appN|          |
 *   +--------(perf)----------+
 *   |        |app1|          |
 *   |        | .  |          |
 *   |        |appN|          |
 *   +-------(phm-perf)-------+
 *   |        |app1|          |
 *   |        | .  |          |
 *   |        |appN|          |
 * release/x.x.0:
 *   +--------(release)-------+
 *   |        |app1|          |
 *   |        | .  |          |
 *   |        |appN|          |
 * hotfix/x.x.1:
 *   +--------(hotfix)--------+
 *   |        |app1|          |
 *   |        | .  |          |
 *   |        |appN|          |
 * master:
 *   +--------(release)-------+
 *   |        |app1|          |
 *   |        | .  |          |
 *   |        |appN|          |
 * <code></pre>
 *
 * @param attrs Additional attributes
 * <ul>
 *    <li>revert: '(deploymentName)' can be used to revert a deployment on catching an exception</li>
 *    <li>smoketest: '(urltemplate)' can be used to smoketest the service after deployment</li>
 * </ul>
 */
def standardDeployments(Map<String, Object> attrs = [:]) {
  currentBuild.result = 'SUCCESS' // we got to deployment, build was successful (for build monitor)

  if(branchName =~ /release/) {
    stage('Deployment') {
      deploymentStage attrs, 'release', 'Release'
    }
  } else if(branchName =~ /hotfix/) {
    stage('Deployment') {
      deploymentStage attrs, 'hotfix', 'Hotfix'
    }
  } else if(branchName == 'master') {
    stage('Deployment') {
      deploymentStage attrs, 'release', 'Release'
    }
  } else {
    if (binding.hasVariable('yamlTemplateList')) {
      stage('Deployment') {
        deploymentStage attrs, 'dev', 'Development'
        deploymentStage attrs, 'int', 'Integration'
        deploymentStage attrs, 'icd', 'ICD'
      }
  } else {
      stage('Deployment') {
        parallel([
            'dev-int' : {
              deploymentStage attrs, 'dev', 'Development'
              deploymentStage attrs, 'int', 'Integration'
            },
            perf      : {
              deploymentStage attrs, 'perf', 'Performance'
            },
            'phm-perf': {
              deploymentStage attrs, 'phm-perf', 'PHM-Performance'
            },
            icd       : {
              deploymentStage attrs, 'icd', 'ICD'
            }
        ])
      }
    }
  }
}


/**
 * Creates a deployment pipeline for CDO.
 *
 *
 *  develop:
 *    +------(cdo-dev)-------+
 *
 * @param attrs Additional attributes
 * <ul>
 *    <li>revert: '(deploymentName)' can be used to revert a deployment on catching an exception</li>
 *    <li>smoketest: '(urltemplate)' can be used to smoketest the service after deployment</li>
 * </ul>
 */
def cdoDeployments(Map<String, Object> attrs = [:]) {
  currentBuild.result = 'SUCCESS' // we got to deployment, build was successful (for build monitor)

  stage('Deployment') {
    deploymentStage attrs, 'cdo-dev', 'CDO-dev'
  }
}

/**
 * Creates a deployment stage
 *
 * @param attrs Additional attributes
 * <ul>
 *    <li>revert: '(deploymentName)' can be used to revert a deployment on catching an exception</li>
 *    <li>smoketest: '(urltemplate)' can be used to smoketest the service after deployment</li>
 * </ul>
 * @param namespace The name of the namespace to be used for the deployment
 * @param displayName The name to be used when sending notifications
 */
def deploymentStage(Map<String, Object> attrs = [:], String namespace, String displayName) {
  stage(namespace) {
    if (attrs.revert) {
      def newAttrs = new HashMap(attrs)
      newAttrs.remove('revert')
      try {
        deploymentStage(newAttrs, namespace, displayName)
      } catch (error) {
        node(nodeName) {
          sh "kubectl -n $namespace delete deploy ${attrs.revert}"
        }
        throw error
      }
    } else {
      try {
        timeout(time: 24, unit: 'HOURS') {
          def approvedBy = input(
              message: "Push to $displayName?",
              submitter: 'TranscendInsights',
              submitterParameter: 'submitter'
          )
          if (binding.hasVariable('yamlTemplateList')) {
            def stepsParallelDeploy = yamlTemplateList.collectEntries {
              try {
                eachParallelName = namespace + ':' + getDeploymentTemplatePath(it)[2]
              }catch (Exception e) {
                println "Exception occurred while retrieving yaml filename: $e"
              }
              [(eachParallelName): stepParallelDeploy(attrs, it, namespace)]
            }
            parallel stepsParallelDeploy
          } else {
            // Failure to click approval will throw FlowInterruptedException
            node(nodeName) {
              unstash(name: stashName)
              kubeDeploy attrs, yamlTemplate, namespace, versionIdentifier
              //notifyOfDeployment displayName, approvedBy
            }
            if (attrs.smoketest) {
              sleep time: 5, unit: 'MINUTES'
              node(nodeName) {
                smokeTest attrs.smoketest.replace('$ns', namespace), displayName, stashName
              }
            }
          }
        }
      }
      // This exception means that it reached the timeout
      catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException error) {
        def abortUser = error.causes.get(0).getUser().toString()
        if (abortUser == 'SYSTEM') {
          echo 'Deployment cancelled by lack of input before timeout'
        }
        else {
          echo 'Deployment cancelled by ' + abortUser
        }
      }
    }
  }
}

def stepParallelDeploy(Map<String, Object> attrs = [:], String yamlTemplate, String namespace) {
  return {
    node(nodeName) {
      unstash(name: stashName)
      kubeDeployMultiple attrs, yamlTemplate, namespace, versionIdentifier
    }
  }
}

/**
 * Check if the build is from a branch or a PR
 * @param branchName Remove this parameter once all Jenkinsfile is
 * updated with isBranch() instead of isBranch(env.BRANCH_NAME)
 */
def isBranch(String branchName = null) {
  return  (env.BRANCH_NAME =~ /^PR-\d+$/) ?: true
}

/**
 * Centralize release versioning process by defining Sprint number and version
 * in one place instead of defining in Jenkinsfile of every repo.
 * @return Map with version info: Sprint number(SPRINT), major.minor.patch(MMP)
 */
def getSemanticVersion(String branchName = null) {
  def ghWebUrl = System.getenv('GITHUB_WEB_URL')
  def ghToken = System.getenv('GITHUB_TOKEN')
  def response = ["curl","-H", "Authorization: token "+ ghToken, "-H", \
      "Accept: application/vnd.github.v3.raw", ghWebUrl + "raw/transcendinsights/ci-release/master/version.json"]
  def versionJson = response.execute().text
  def versionInfo = new JsonSlurper().parseText(versionJson)
  def SPRINT = ''
  def MMP = ''

  if ( ! branchName ) {
    branchName = (env.BRANCH_NAME =~ /PR-\d+/) ? env.CHANGE_TARGET : env.BRANCH_NAME
  }
  def LABEL = branchName.contains('release') ? 'rc.1' : 'snapshot'
  Map<String, String> APP_VERSION
  final Map<String, String> appVersion = new HashMap<>()

  if (versionInfo.projects?.get(branchName)?.sprint) {
    SPRINT = versionInfo.projects.get(branchName).sprint
    appVersion.put "SPRINT", SPRINT
  } else {
    throw new RuntimeException("Cannot access json property ${branchName}/sprint")
  }
  if (versionInfo.projects?.get(branchName)?.mmp) {
    MMP = versionInfo.projects.get(branchName).mmp
    appVersion.put "MMP", MMP
  } else {
    throw new RuntimeException("Cannot access json property ${branchName}/mmp")
  }
  appVersion.put("VERSION", "${MMP}-${LABEL}-${SPRINT}-build.${env.BUILD_NUMBER}")
  APP_VERSION = Collections.unmodifiableMap(appVersion)
  return APP_VERSION
}


/*Check if current week is of new sprint
  Our sprint numbers are always odd,
  and we start new sprints in Wednesday
*/
def isNextSprint() {
  Calendar now = Calendar.getInstance()
  def week = now.get(Calendar.WEEK_OF_YEAR)
  def day  = now.get(Calendar.DAY_OF_WEEK)
  Boolean isNextSprint = false
  if (week % 2 == 0) {
    if (day == Calendar.WEDNESDAY) {
      isNextSprint = true
    }
  }
  return isNextSprint
}

/*Prepare new version info basing on
  new sprint/MMP ID
  Output is a list including:
    - newJson: the new JSON string that is used to generate new version.json file
    - SPRINT: the new sprint ID in the current year
    - MMP: the release ID
*/
def prepareVersionInfo() {
  def MMP = getSemanticVersion('develop').MMP
  def SPRINT = getSemanticVersion('develop').SPRINT
  def ghWebUrl = System.getenv('GITHUB_WEB_URL')
  def ghToken = System.getenv('GITHUB_TOKEN')
  def response = ["curl","-H", "Authorization: token "+ ghToken, "-H", \
      "Accept: application/vnd.github.v3.raw", ghWebUrl + "raw/transcendinsights/ci-release/master/version.json"]
  def versionJson = response.execute().text
  def versionInfo = new JsonSlurper().parseText(versionJson)
  def builder = new JsonBuilder(versionInfo)

 //MMP ID in next sprint
  def MMP_SPLIT = MMP.tokenize('.')
  def MAJOR = MMP_SPLIT[0]
  def MINOR = MMP_SPLIT[1].toInteger() + 1
  def PATCH = MMP_SPLIT[2]
  MMP = MAJOR + '.' + MINOR + '.' + PATCH

  /*Next SPRINT number. Max is 26. Why?
  Each of our sprint is 2 week long
  There are max 52 weeks per year*/
  def SPRINT_SPLIT = SPRINT.tokenize('.')
  def SPRINT_NUMBER = SPRINT_SPLIT[2].toInteger()
  if (SPRINT_NUMBER ==  26) {
    SPRINT_NUMBER = 1
  } else {
    SPRINT_NUMBER++
  }
  Calendar now = Calendar.getInstance()
  def SPRINT_YEAR = now.get(Calendar.YEAR)
  SPRINT = 'sprint' + '.' + SPRINT_YEAR + '.' + SPRINT_NUMBER

  def release_branch = 'release' + '/' + MMP
  builder.content.projects.master.sprint = SPRINT
  builder.content.projects.master.mmp = MMP
  builder.content.projects.develop.sprint = SPRINT
  builder.content.projects.develop.mmp = MMP

  def builderX = new JsonBuilder()
  def jsonNew = builderX.release_branch {
        "sprint" SPRINT
        "mmp" MMP
  }

  builder.content.projects."$release_branch" = jsonNew.release_branch
  def newJson = builder.toPrettyString()
  return [ newJson, SPRINT, MMP ]
}

/*Update current version.json file basing on 
  results of prepareVersionInfo()
*/
def updateVersionFile(String newJson, String SPRINT, String MMP) {

  def newVersion = 'newVersion.json'
  def fileWriter = new File(newVersion)
  fileWriter.write(newJson)

  def gitUsername = System.getenv('GIT_USER') 
  def gitPassword = System.getenv('GIT_PASS') 
  def ghWebUrl = "http://" + gitUsername + ":" + gitPassword + "@github.transcendinsights.com"
  def ghRepo = "/ci-release"
  def tmpDir = "ci-release-tmp"

  def pushNewVersionFile = \
    "rm -Rf " + tmpDir + " && " + \
    "git clone " + ghWebUrl + ghRepo + " " + tmpDir + " && " + \
    "mv " + newVersionFile + " " + tmpDir + "/version.json" + " && " + \
    "git config --global user.name \'Build Team\'" + " && " + \
    "git config --global user.email \'build_engineers@transcendinsights.com\'" + " && " + \
    "cd " + tmpDir + " && " + "git add version.json" + " && " + \
    "git commit -m \'" + SPRINT + " release " + MMP + "\'" + " && " + \
    "git push"
  ["/bin/sh", "-c", pushNewVersionFile].execute()
}

