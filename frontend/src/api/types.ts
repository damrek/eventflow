/** Pagination envelope shared by every service (`PageResponse` in the backend). */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
