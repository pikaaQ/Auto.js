/*
 * @Author: TonyJiangWJ
 * @Date: 2020-04-25 20:25:10
 * @Last Modified by: TonyJiangWJ
 * @Last Modified time: 2023-08-21 14:55:56
 * @Description: 导入单例模式的工具类
 *
 * 路径解析规则:
 *   ./xxx, ../xxx  → 相对于调用文件所在目录解析
 *   /xxx           → 从项目根目录解析
 *   xxx（裸名称）    → 等同于 /xxx
 */

let printExceptionStack = require('./PrintExceptionStack.js')

const projectRoot = (function () {
  // AutoX.js 不支持 __dirname，从 Error stack 获取本文件路径
  let dir = files.cwd()
  try {
    let stack = new Error().stack
    let lines = stack.split('\n')
    for (let i = 0; i < lines.length; i++) {
      let m = lines[i].match(/\((.+?\.js):\d+:\d+\)/) || lines[i].match(/at (.+?\.js):\d+:\d+/)
      if (m && m[1].indexOf('SingletonRequirer') !== -1) {
        dir = m[1].substring(0, m[1].lastIndexOf('/'))
        break
      }
    }
  } catch (e) {}
  if (dir.endsWith('/lib')) {
    return dir.substring(0, dir.length - 4)
  }
  return dir
})()

function getCallerFile() {
  let stack = new Error().stack
  let lines = stack.split('\n')
  for (let i = 3; i < lines.length; i++) {
    let m = lines[i].match(/\((.+?\.js):\d+:\d+\)/) || lines[i].match(/at (.+?\.js):\d+:\d+/)
    if (m && m[1].indexOf('SingletonRequirer') === -1) {
      return m[1]
    }
  }
  return null
}

function resolveRelativePath(baseDir, relativePath) {
  let parts = baseDir.split('/')
  let rel = relativePath.split('/')
  for (let p of rel) {
    if (p === '..') {
      if (parts.length > 0) parts.pop()
    } else if (p !== '.' && p !== '') {
      parts.push(p)
    }
  }
  return parts.join('/')
}

function resolveModulePath(modulePath) {
  if (modulePath.startsWith('/')) {
    return projectRoot + modulePath
  }
  if (modulePath.startsWith('./') || modulePath.startsWith('../')) {
    let caller = getCallerFile()
    let callerDir = caller ? caller.substring(0, caller.lastIndexOf('/')) : projectRoot
    return resolveRelativePath(callerDir, modulePath)
  }
  return projectRoot + '/' + modulePath
}

function tryRequireOnce(filePath) {
  try {
    return require(filePath)
  } catch (e) {
    return null
  }
}

function tryRequire(filePath) {
  let result = tryRequireOnce(filePath)
  if (result) return result
  if (filePath.lastIndexOf('.js') !== filePath.length - 3) {
    result = tryRequireOnce(filePath + '.js')
    if (result) return result
  } else {
    let withoutExt = filePath.substring(0, filePath.length - 3)
    result = tryRequireOnce(withoutExt)
    if (result) return result
  }
  return null
}

module.exports = function (_runtime_, scope) {
  if (typeof scope.singletonRequirer === 'undefined') {
    scope.singletonRequirerInfo = {
      useCount: 0,
      moduleMap: {}
    }

    scope._singletonCache = {}

    scope.singletonRequirer = function (modulePath, showRequireInfo) {
      let resolvedPath = resolveModulePath(modulePath)
      if (typeof scope._singletonCache[resolvedPath] === 'undefined') {
        let prototypes = tryRequire(resolvedPath)
        if (!prototypes) {
          console.error('导入模块失败：[' + modulePath + '] 解析路径：' + resolvedPath + '，请检查代码')
          toast('导入模块失败：[' + modulePath + ']')
          exit()
        }
        scope._singletonCache[resolvedPath] = prototypes
        scope.singletonRequirerInfo.moduleMap[modulePath] = {
          useCount: 0
        }
      } else {
        if (!scope.singletonRequirerInfo.moduleMap[modulePath]) {
          scope.singletonRequirerInfo.moduleMap[modulePath] = { useCount: 0 }
        }
      }
      scope.singletonRequirerInfo.moduleMap[modulePath].useCount += 1

      if (showRequireInfo) {
        console.info('singletonRequirer调用次数：' + scope.singletonRequirerInfo.useCount)
        Object.keys(scope.singletonRequirerInfo.moduleMap).forEach(function (key) {
          console.info('module: ' + key + ' 调用次数：' + scope.singletonRequirerInfo.moduleMap[key].useCount)
        })
      }
      return scope._singletonCache[resolvedPath]
    }
  }
  scope.singletonRequirerInfo.useCount += 1
  return scope.singletonRequirer
}
