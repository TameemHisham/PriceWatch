export type ChartPoint = {
    date: string;
    runningMin: number | null;
    [marketplace: string]: number | string | null;
};
