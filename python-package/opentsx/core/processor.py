"""TSProcessor abstraction for processing pipelines."""

from abc import ABC, abstractmethod
from typing import Union, List, Optional
from dataclasses import dataclass


@dataclass
class ProcessorConfig:
    """Configuration for processors."""
    name: str
    parameters: dict
    parallel: bool = False


class TSProcessor(ABC):
    """Abstract base class for time series processors."""

    def __init__(self, config: Optional[ProcessorConfig] = None):
        self.config = config or ProcessorConfig(
            name=self.__class__.__name__,
            parameters={}
        )

    @abstractmethod
    def process(self, input_data: Union["TimeSeriesObject", "TSBucket"]) -> Union["TimeSeriesObject", "TSBucket"]:
        """Process time series data."""
        pass

    def __call__(self, input_data: Union["TimeSeriesObject", "TSBucket"]) -> Union["TimeSeriesObject", "TSBucket"]:
        """Allow processor to be called directly."""
        return self.process(input_data)

    def chain(self, next_processor: "TSProcessor") -> "ProcessorChain":
        """Chain this processor with another."""
        return ProcessorChain([self, next_processor])

    def __rshift__(self, other: "TSProcessor") -> "ProcessorChain":
        """Allow chaining with >> operator."""
        return self.chain(other)


class ProcessorChain(TSProcessor):
    """Chain multiple processors together."""

    def __init__(self, processors: List[TSProcessor]):
        super().__init__(ProcessorConfig(name="ProcessorChain", parameters={}))
        self.processors = processors

    def process(self, input_data: Union["TimeSeriesObject", "TSBucket"]) -> Union["TimeSeriesObject", "TSBucket"]:
        """Apply all processors in sequence."""
        result = input_data
        for processor in self.processors:
            result = processor.process(result)
        return result
