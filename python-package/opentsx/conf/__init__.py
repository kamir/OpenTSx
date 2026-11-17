"""OpenTSx configuration: the key registry, the six sources and the loader of the opentsx command line."""

from opentsx.conf.conf import Loaded, OpenTsxConf, OpenTsxConfError, Registry, readFile

__all__ = ["Loaded", "OpenTsxConf", "OpenTsxConfError", "Registry", "readFile"]
