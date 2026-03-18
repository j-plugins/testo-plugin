<?php

namespace Tests;

use Testo\Sample\DataProvider;
use Testo\Data\DataSet;

class DataProviderTest
{
    #[DataProvider(provider: 'provideData')]
    public function testWithProvider(): void
    {
    }

    public static function provideData(): iterable
    {
        yield [1, 2];
        yield [3, 4];
        return [5, 6];
    }

    #[DataSet]
    public function testWithDataSet(): void
    {
    }
}
