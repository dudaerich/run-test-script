#!/bin/env groovy

@Grapes(
@Grab(group='org.atteo.dollarbrace', module='dollarbrace', version='2.1')
)

import groovy.json.JsonSlurper
import org.atteo.dollarbrace.DollarBrace
import org.atteo.dollarbrace.PropertyResolver
import org.atteo.dollarbrace.PropertiesPropertyResolver
import org.atteo.dollarbrace.SystemPropertyResolver
import org.atteo.dollarbrace.EnvironmentPropertyResolver

def profileName = args[0]
def testName = args[1]
def arguments = args.length >= 3 ? args[2..-1] : []

class Profile {

  def jsonSlurper = new JsonSlurper()
  def vars = {}
  def resolver = null
  def env = null

  void load(String name) {
    File profileFile = locateProfileFile(name)
    vars = jsonSlurper.parse(profileFile)
  }

  File locateProfileFile(String name) {

    def locations = [
      '.',
      "${System.getProperty('user.home')}/.config/run-test-script"
    ]

    for (def location : locations) {
      File f = new File(location, name)
        if (f.exists()) {
          return f
        }
    }
    throw new IllegalArgumentException("The profile named \"$name\" wasn't found.")
  }

  void preparePropertyResolver(String testName, def arguments) {

    vars.getMetaClass().union {
      def mapDelegate = delegate
      it.each {k, v ->

        if (mapDelegate[k]) {
          if (v instanceof String && v.startsWith('+')) {
            mapDelegate[k] = mapDelegate[k] + ' ' + v.substring(1)
          } else {
            mapDelegate[k] = v
          }
        } else {
          mapDelegate[k] = v
        }
      }
      return mapDelegate
    }

    vars['TEST'] = testName
    def profiles = getProfiles(arguments)
    profiles.each { vars = vars.union(it) }

    if (vars['PWD'] == null) {
      vars['PWD'] = System.getenv('PWD')
    }

    vars = evaluateBashExpressions(vars)

    PropertyResolver propertyResolver = new PropertiesPropertyResolver(vars as Properties)
    PropertyResolver systemPropertyResolver = new SystemPropertyResolver()
    PropertyResolver envResolver = new EnvironmentPropertyResolver()
    resolver = DollarBrace.getFilter(propertyResolver, systemPropertyResolver, envResolver)
  }

  def evaluateBashExpressions(def expr) {

    if (expr.getMetaClass().respondsTo(expr, "collectEntries")) {

      return expr.collectEntries { k,v -> [k, evaluateBashExpressions(v)]}

    } else if (!(expr instanceof String) && expr.getMetaClass().respondsTo(expr, "collect")) {

      return expr.collect{ evaluateBashExpressions(it) }

    } else {

      if (expr != null && expr.startsWith('$(') && expr.endsWith(')')) {
        return ['/bin/bash', '-c', expr[2..-2]].execute().text.trim()
      } else {
        return expr
      }

    }

  }

  def getProfiles(def arguments) {
    def profiles = ['default']

    arguments.each {
      if (it.startsWith('+')) {
        profiles << it.substring(1)
      }
    }

    profiles.collect {
      def profile = it
      while (profile instanceof String) {
        profile = vars.profiles[profile]
      }
      return profile
    }
  }

  String get(String name, boolean failIfNotExists = true) {
    if (vars[name] != null) {
      return resolver.filter(vars[name])
    } else {
      if (failIfNotExists) {
        throw new IllegalArgumentException("The variable named \"$name\" wasn't found.")
      } else {
        return null
      }
    }
  }

  def getEnv() {
    if (env == null) {
      env = [:]
      vars.ENV.each { k, v -> env[k] = resolver.filter(v) }
    }
    return env
  }

  void runTest(String testName, def arguments) {
    preparePropertyResolver(testName, arguments)

    def cmd = "${get('CMD')} ${arguments.findAll({!it.startsWith('+')}).join(' ')}"
    def cwd = get('CWD')

    if ('--dry-run' in arguments) {
      getEnv().each { k, v -> println "$k:$v" }
      println "CWD: $cwd"
      println "$cmd"
      return
    }

    def pb = new ProcessBuilder("/bin/bash", "-c", cmd)
    def env = pb.environment()
    env.putAll(getEnv())
    pb.directory(new File(cwd))

    def process = pb.start()

    def streamCaptures = []

    def out = get('OUT', false)

    if (out != null) {
      new File(out).withOutputStream { stream ->
        streamCaptures << new StreamCapture(is: process.inputStream, os: stream)
        streamCaptures << new StreamCapture(is: process.errorStream, os: stream)
        streamCaptures.each { it.start() }
        streamCaptures.each { it.join() }
      }
    }
    process.waitFor()

  }

  class StreamCapture extends Thread {

    InputStream is
    OutputStream os

    void run() {
      is.eachLine 'UTF-8', {
        os << it << '\n'
        println it
      }
    }

  }
}

Profile profile = new Profile()
profile.load(profileName)
profile.runTest(testName, arguments)
