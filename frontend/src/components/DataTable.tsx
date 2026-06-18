import { useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";

interface DataTableProps {
  headers: string[];
  children: ReactNode;
}

export function DataTable({ headers, children }: DataTableProps) {
  return (
    <div className="overflow-hidden rounded-md border border-line bg-white shadow-sm">
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-line text-left text-sm">
          <thead className="bg-mist">
            <tr>
              {headers.map((header) => (
                <th key={header} className="px-4 py-3 font-semibold text-slate-700">
                  {header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-line">{children}</tbody>
        </table>
      </div>
    </div>
  );
}

interface PaginatedDataTableProps<T> {
  headers: string[];
  rows: T[];
  renderRow: (row: T) => ReactNode;
  initialPageSize?: number;
  pageSizeOptions?: number[];
  totalRows?: number;
  page?: number;
  pageSize?: number;
  onPageChange?: (page: number) => void;
  onPageSizeChange?: (pageSize: number) => void;
}

export function PaginatedDataTable<T>({
  headers,
  rows,
  renderRow,
  initialPageSize = 10,
  pageSizeOptions = [10, 25, 50, 100],
  totalRows,
  page: controlledPage,
  pageSize: controlledPageSize,
  onPageChange,
  onPageSizeChange
}: PaginatedDataTableProps<T>) {
  const [localPage, setLocalPage] = useState(1);
  const [localPageSize, setLocalPageSize] = useState(initialPageSize);
  const isControlled = controlledPage !== undefined && controlledPageSize !== undefined;
  const page = controlledPage ?? localPage;
  const pageSize = controlledPageSize ?? localPageSize;
  const rowTotal = totalRows ?? rows.length;
  const pageCount = Math.max(1, Math.ceil(rowTotal / pageSize));
  const firstItem = rowTotal === 0 ? 0 : (page - 1) * pageSize + 1;
  const lastItem = Math.min(rowTotal, isControlled ? (page - 1) * pageSize + rows.length : page * pageSize);

  function setPage(nextPage: number) {
    if (onPageChange) {
      onPageChange(nextPage);
    } else {
      setLocalPage(nextPage);
    }
  }

  function setPageSize(nextPageSize: number) {
    if (onPageSizeChange) {
      onPageSizeChange(nextPageSize);
    } else {
      setLocalPageSize(nextPageSize);
    }
  }

  useEffect(() => {
    if (!isControlled) {
      setLocalPage(1);
    }
  }, [isControlled, rows, pageSize]);

  useEffect(() => {
    if (!isControlled) {
      setLocalPage((currentPage) => Math.min(currentPage, pageCount));
    }
  }, [isControlled, pageCount]);

  const pageRows = useMemo(() => {
    if (isControlled) {
      return rows;
    }
    const start = (page - 1) * pageSize;
    return rows.slice(start, start + pageSize);
  }, [isControlled, page, pageSize, rows]);

  return (
    <div className="overflow-hidden rounded-md border border-line bg-white shadow-sm">
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-line text-left text-sm">
          <thead className="bg-mist">
            <tr>
              {headers.map((header) => (
                <th key={header} className="px-4 py-3 font-semibold text-slate-700">
                  {header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-line">{pageRows.map(renderRow)}</tbody>
        </table>
      </div>
      <div className="flex flex-col gap-3 border-t border-line bg-white px-4 py-3 text-sm text-slate-600 sm:flex-row sm:items-center sm:justify-between">
        <span>
          Showing {firstItem}-{lastItem} of {rowTotal}
        </span>
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-2">
            <span>Rows</span>
            <select
              value={pageSize}
              onChange={(event) => setPageSize(Number(event.target.value))}
              className="min-h-9 py-1"
            >
              {pageSizeOptions.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="min-h-9 rounded-md border border-line px-3 py-1 font-semibold text-slate-700 disabled:cursor-not-allowed disabled:text-slate-400"
            disabled={page === 1}
            onClick={() => setPage(Math.max(1, page - 1))}
          >
            Previous
          </button>
          <span className="min-w-20 text-center">
            {page} / {pageCount}
          </span>
          <button
            type="button"
            className="min-h-9 rounded-md border border-line px-3 py-1 font-semibold text-slate-700 disabled:cursor-not-allowed disabled:text-slate-400"
            disabled={page === pageCount}
            onClick={() => setPage(Math.min(pageCount, page + 1))}
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}
