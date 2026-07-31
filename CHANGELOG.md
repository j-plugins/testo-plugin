<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# testo-plugin Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

### Fixed
- Concurrent tests no longer nest inside one another in the test tree: the tree is built from the `nodeId`/`parentNodeId`
  Testo reports rather than from the order messages arrive in.
