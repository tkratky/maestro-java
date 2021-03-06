/*
 * Copyright 2018 Otavio R. Piske <angusyoung@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.maestro.plotter.common.graph;

import org.knowm.xchart.XYChart;
import org.maestro.plotter.common.ReportData;

/**
 * A default histogram plotter
 * @param <T>
 */
public abstract class DefaultHistogramPlotter<T extends ReportData> extends AbstractPlotter<T> {

    protected XYChart createChart() {
        return baseChart();
    }

}
